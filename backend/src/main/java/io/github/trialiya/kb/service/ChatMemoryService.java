package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@AllArgsConstructor
@Slf4j
@Service
public class ChatMemoryService implements ChatMemoryRepository {

    /**
     * Инструменты, отметки о вызове которых не сохраняем: служебные либо те, что полезно звать
     * заново.
     */
    private static final Set<String> SKIP_TOOLS =
            Set.of(
                    "recordChatInsights",
                    "getUserName",
                    "getCurrentDateTime",
                    "getOriginalMessages");

    /** Возвращает {@code true}, если детали вызова инструмента сохраняются в БД. */
    public boolean hasDetails(String toolName) {
        return !SKIP_TOOLS.contains(toolName);
    }

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMessageRepository.deleteChatMessageByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // Сообщение + его протокольные tool-данные, извлечённые один раз (см. toolDataOf).
        record Pending(Message message, @Nullable ToolData toolData) {}

        final AtomicLong lastPosition = new AtomicLong();
        final List<ChatMessageEntity> newMessagesToSave =
                messages.stream()
                        // Уже сохранённые (IMessage) пропускаем, но фиксируем их позицию —
                        // этот фильтр обязан идти ПЕРВЫМ: у TOOL-обёрток текст пустой, и
                        // blank-фильтр ниже потерял бы их позицию.
                        .filter(
                                message -> {
                                    if (message instanceof IMessage iMessage) {
                                        lastPosition.set(iMessage.chatMessage().getPosition());
                                        return false;
                                    }
                                    return true;
                                })
                        .map(message -> new Pending(message, toolDataOf(message)))
                        // Протокольные tool-сообщения (assistant c tool_calls, ответы
                        // инструментов) сохраняем даже с пустым текстом — без них пара
                        // assistant.tool_calls ↔ tool.tool_call_id не восстановится.
                        .filter(
                                pending ->
                                        Strings.isNotBlank(pending.message().getText())
                                                || pending.toolData() != null)
                        .map(
                                pending ->
                                        new ChatMessageEntity(
                                                0,
                                                conversationId,
                                                pending.message().getText() == null
                                                        ? ""
                                                        : pending.message().getText(),
                                                pending.message().getMessageType(),
                                                lastPosition.incrementAndGet(),
                                                false,
                                                false,
                                                LocalDateTime.now(),
                                                null,
                                                pending.toolData()))
                        .toList();
        chatMessageRepository.saveAll(newMessagesToSave);
    }

    /**
     * Чинит оборванную пару tool-сообщений в хвосте диалога. Если прогон прервали (stop, ошибка,
     * падение процесса) во время выполнения инструментов, последняя строка — ASSISTANT с tool_calls
     * без парной TOOL-строки; следующий запрос к модели с таким хвостом получил бы 400
     * (assistant.tool_calls без tool-ответов). Достраиваем синтетический TOOL-ответ.
     *
     * <p>Оборванной может быть только последняя пара: цикл строго чередует assistant(tool_calls) →
     * tool, и всё, что раньше хвоста, уже сохранено парами.
     */
    @Transactional
    public void repairDanglingToolCalls(String conversationId) {
        chatMessageRepository
                .findFirstByConversationIdOrderByPositionDesc(conversationId)
                .filter(last -> last.getType() == MessageType.ASSISTANT)
                .filter(
                        last ->
                                last.getToolData() != null
                                        && last.getToolData().toolCalls() != null
                                        && !last.getToolData().toolCalls().isEmpty())
                .ifPresent(
                        last -> {
                            final List<ToolData.Response> responses =
                                    last.getToolData().toolCalls().stream()
                                            .map(
                                                    c ->
                                                            new ToolData.Response(
                                                                    c.id(),
                                                                    c.name(),
                                                                    "[interrupted — no result]"))
                                            .toList();
                            log.info(
                                    "Repairing dangling tool_calls tail for {} ({} synthetic responses)",
                                    conversationId,
                                    responses.size());
                            chatMessageRepository.save(
                                    new ChatMessageEntity(
                                            0L,
                                            conversationId,
                                            "",
                                            MessageType.TOOL,
                                            last.getPosition() + 1,
                                            false,
                                            false,
                                            LocalDateTime.now(),
                                            null,
                                            new ToolData(null, responses)));
                        });
    }

    /** Протокольные tool-данные сообщения, если они есть (иначе {@code null}). */
    private static @Nullable ToolData toolDataOf(Message message) {
        if (message instanceof AssistantMessage assistantMessage
                && assistantMessage.hasToolCalls()) {
            return ToolData.from(assistantMessage);
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && !toolResponseMessage.getResponses().isEmpty()) {
            return ToolData.from(toolResponseMessage);
        }
        return null;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return chatMessageRepository
                .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                        conversationId)
                .stream()
                .map(ChatMessageEntity::getMessage)
                .map(Message.class::cast)
                .toList();
    }

    @Override
    public List<String> findConversationIds() {
        return chatTopicRepository.findIdsByUserOrderByUpdatedAtDesc(ChatUtils.getUser());
    }

    public List<ChatMessageEntity> findChatMessageByConversationId(String conversationId) {
        return chatMessageRepository
                .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                        conversationId);
    }

    public Page findLatestPage(String conversationId, int limit) {
        return toPage(chatMessageRepository.findLatest(conversationId, limit + 1), limit);
    }

    public Page findPageBefore(
            String conversationId, LocalDateTime beforeCreatedAt, long beforeId, int limit) {
        return toPage(
                chatMessageRepository.findBefore(
                        conversationId, beforeCreatedAt, beforeId, limit + 1),
                limit);
    }

    private Page toPage(List<ChatMessageEntity> rowsDesc, int limit) {
        boolean hasMore = rowsDesc.size() > limit;
        List<ChatMessageEntity> chrono =
                (hasMore ? rowsDesc.subList(0, limit) : rowsDesc).reversed();
        MessageCursor cursor =
                chrono.isEmpty()
                        ? null
                        : new MessageCursor(
                                chrono.getFirst().getCreatedAt(), chrono.getFirst().getId());
        return new Page(chrono, hasMore, cursor);
    }

    public record Page(
            List<ChatMessageEntity> messages,
            boolean hasMore,
            @Nullable MessageCursor oldestCursor) {}

    /**
     * Полные детали одного вызова инструмента, собранные из {@code chat_message}: UI-мета
     * (name/status/error/resultMeta) — из {@code meta.invocations} ASSISTANT-сегмента прогона,
     * полные аргументы — из его {@code tool_data.toolCalls}, полный результат — из {@code
     * tool_data.responses} последующего TOOL-сообщения по {@code tool_call_id}.
     *
     * <p>Сопоставление callIndex → позиция в {@code toolCalls} сегмента: callIndex сквозной по
     * прогону и совпадает с хронологическим номером вызова, а сегменты потребляют вызовы прогона
     * подряд (см. {@link #attachRunMeta}) — значит, позиция внутри сегмента равна {@code callIndex
     * − Σ toolCalls предыдущих сегментов прогона}. {@code SKIP_TOOLS} вырезаны только из
     * invocations, в {@code toolCalls} они остаются, поэтому смещение считается по toolCalls.
     */
    public Optional<ToolCallDetail> findToolCallDetail(
            String conversationId, String runId, int callIndex) {
        final List<ChatMessageEntity> all = findChatMessageByConversationId(conversationId);
        int offset = 0;
        for (int i = 0; i < all.size(); i++) {
            final ChatMessageEntity segment = all.get(i);
            if (segment.getType() != MessageType.ASSISTANT
                    || segment.getMeta() == null
                    || !runId.equals(segment.getMeta().runId())) {
                continue;
            }
            final List<ToolData.Call> calls =
                    segment.getToolData() != null && segment.getToolData().toolCalls() != null
                            ? segment.getToolData().toolCalls()
                            : List.of();
            final Optional<ToolInvocationMeta> invocation =
                    segment.getMeta().invocations().stream()
                            .filter(inv -> Objects.equals(inv.callIndex(), callIndex))
                            .findFirst();
            if (invocation.isEmpty()) {
                offset += calls.size();
                continue;
            }
            final int position = callIndex - offset;
            final ToolData.Call call =
                    position >= 0 && position < calls.size() ? calls.get(position) : null;
            return Optional.of(
                    new ToolCallDetail(
                            callIndex,
                            invocation.get().name(),
                            call != null ? call.arguments() : null,
                            invocation.get().status(),
                            invocation.get().error(),
                            call != null ? findResponseData(all, i, call.id()) : null,
                            invocation.get().resultMeta(),
                            segment.getCreatedAt()));
        }
        return Optional.empty();
    }

    /** Результат вызова из первого TOOL-сообщения после сегмента с ответом на {@code callId}. */
    private static @Nullable String findResponseData(
            List<ChatMessageEntity> all, int segmentIndex, String callId) {
        for (int j = segmentIndex + 1; j < all.size(); j++) {
            final ChatMessageEntity message = all.get(j);
            if (message.getType() != MessageType.TOOL
                    || message.getToolData() == null
                    || message.getToolData().responses() == null) {
                continue;
            }
            for (ToolData.Response response : message.getToolData().responses()) {
                if (Objects.equals(response.id(), callId)) {
                    return response.responseData();
                }
            }
        }
        return null;
    }

    /**
     * Прикрепляет UI-метаданные вызовов инструментов к ASSISTANT-сегментам прогона, которые
     * advisor-цепочка уже сохранила с протокольными tool_calls (см. {@link #saveAll}). Сегменты
     * идут в порядке позиций, каждый потребляет из общего списка вызовов столько, сколько у него
     * tool_calls — список вызовов прогона хронологический, поэтому сопоставление однозначно.
     *
     * <p>Рассматриваются только сегменты после последнего USER-сообщения: прогоны на чат строго
     * последовательны, значит всё, что после последнего вопроса, относится к текущему прогону, а
     * необогащённые сегменты старых аварийных прогонов не перехватят чужие вызовы.
     *
     * <p>{@code SKIP_TOOLS} вырезаются только из UI-метаданных — протокольные tool_calls сегмента
     * остаются полными, иначе модель получила бы рассинхронизированную пару tool-сообщений.
     */
    @Transactional
    public void attachRunMeta(
            String conversationId, String runId, @Nullable List<ToolInvocation> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        final List<ChatMessageEntity> all =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId);
        int lastUser = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getType() == MessageType.USER) {
                lastUser = i;
            }
        }
        final List<ChatMessageEntity> segments =
                all.subList(lastUser + 1, all.size()).stream()
                        .filter(e -> e.getType() == MessageType.ASSISTANT)
                        .filter(e -> e.getMeta() == null)
                        .filter(
                                e ->
                                        e.getToolData() != null
                                                && e.getToolData().toolCalls() != null
                                                && !e.getToolData().toolCalls().isEmpty())
                        .toList();
        int cursor = 0;
        for (ChatMessageEntity segment : segments) {
            final int end =
                    Math.min(cursor + segment.getToolData().toolCalls().size(), toolCalls.size());
            if (cursor >= end) {
                break;
            }
            final List<ToolInvocationMeta> metas =
                    toolCalls.subList(cursor, end).stream()
                            .filter(tc -> !SKIP_TOOLS.contains(tc.name()))
                            .map(tc -> tc.toMeta(hasDetails(tc.name())))
                            .toList();
            cursor = end;
            chatMessageRepository.save(segment.withMeta(new ChatMessageMeta(runId, false, metas)));
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /** Сколько сырых совпадений сообщений просматриваем при поиске по всем чатам пользователя. */
    private static final int MESSAGE_SEARCH_SCAN_LIMIT = 200;

    /**
     * Контекст (в символах) до и после вхождения при построении сниппета. Префикс намеренно
     * короткий: сниппет в дропдауне обрезается справа, и при длинном префиксе само совпадение
     * оказывалось за границей видимой области.
     */
    private static final int SNIPPET_PREFIX = 30;

    private static final int SNIPPET_SUFFIX = 90;

    /**
     * Сообщение видно пользователю и осмысленно для поиска: не служебное SYSTEM и не
     * legacy-«крошка» вызовов инструментов (meta.toolCalls=true у старых записей) — те же критерии,
     * что и в отображении истории чата.
     */
    private static boolean isSearchable(ChatMessageEntity entity) {
        if (entity.getType() == MessageType.SYSTEM) {
            return false;
        }
        ChatMessageMeta meta = entity.getMeta();
        return meta == null || !meta.toolCalls();
    }

    /** Поиск сообщений внутри одного чата — для локального find-бара (Ctrl+F). */
    public List<MessageSearchHit> searchMessages(String conversationId, String q) {
        String pattern = q == null ? "" : q.trim();
        if (pattern.isEmpty()) {
            return List.of();
        }
        return chatMessageRepository.searchInConversation(conversationId, pattern).stream()
                .filter(ChatMemoryService::isSearchable)
                .map(e -> new MessageSearchHit(e.getId(), e.getCreatedAt()))
                .toList();
    }

    /**
     * Поиск чатов пользователя по названию и/или содержимому сообщений. Результат объединяет оба
     * вида совпадений по чату; сниппет строится вокруг самого свежего совпавшего сообщения.
     */
    public List<ChatSearchResult> searchChats(String user, String q, int limit) {
        String pattern = q == null ? "" : q.trim();
        if (pattern.isEmpty()) {
            return List.of();
        }

        List<ChatTopicEntity> titleHits = chatTopicRepository.searchByTopic(user, pattern);
        Set<String> titleMatchIds = new LinkedHashSet<>();
        Map<String, ChatTopicEntity> topicsById = new LinkedHashMap<>();
        for (ChatTopicEntity t : titleHits) {
            titleMatchIds.add(t.getConversationId());
            topicsById.put(t.getConversationId(), t);
        }

        List<ChatMessageEntity> rawHits =
                chatMessageRepository.searchForUser(user, pattern, MESSAGE_SEARCH_SCAN_LIMIT);
        // rawHits идёт от новых к старым — первое сообщение на conversationId и есть самое свежее.
        Map<String, ChatMessageEntity> latestHitByConversation = new LinkedHashMap<>();
        Map<String, Integer> countByConversation = new HashMap<>();
        for (ChatMessageEntity e : rawHits) {
            if (!isSearchable(e)) {
                continue;
            }
            latestHitByConversation.putIfAbsent(e.getConversationId(), e);
            countByConversation.merge(e.getConversationId(), 1, Integer::sum);
        }

        List<String> missingTopics =
                latestHitByConversation.keySet().stream()
                        .filter(id -> !topicsById.containsKey(id))
                        .toList();
        if (!missingTopics.isEmpty()) {
            // Безопасно без повторной фильтрации по user: эти id уже пришли из
            // searchForUser(user, ...), т.е. и так принадлежат этому пользователю.
            chatTopicRepository
                    .findAllById(missingTopics)
                    .forEach(t -> topicsById.put(t.getConversationId(), t));
        }

        Set<String> allIds = new LinkedHashSet<>(titleMatchIds);
        allIds.addAll(latestHitByConversation.keySet());

        return allIds.stream()
                .map(topicsById::get)
                .filter(Objects::nonNull)
                .map(
                        topic -> {
                            ChatMessageEntity hit =
                                    latestHitByConversation.get(topic.getConversationId());
                            return new ChatSearchResult(
                                    topic.getConversationId(),
                                    topic.getTopic(),
                                    topic.getUpdatedAt(),
                                    titleMatchIds.contains(topic.getConversationId()),
                                    countByConversation.getOrDefault(topic.getConversationId(), 0),
                                    hit != null ? buildSnippet(hit.getContent(), pattern) : null);
                        })
                .sorted(
                        Comparator.comparing(
                                        (ChatSearchResult r) ->
                                                r.updatedAt() != null
                                                        ? r.updatedAt()
                                                        : LocalDateTime.MIN)
                                .reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Фрагмент текста вокруг первого вхождения query (без учёта регистра), с многоточиями. Переносы
     * строк и повторные пробелы схлопываются: сниппет — одна плотная строка, а не первая (часто
     * пустая или заголовочная) строка markdown-сообщения. Package-private для юнит-теста.
     */
    static @Nullable String buildSnippet(@Nullable String content, String query) {
        if (content == null) {
            return null;
        }
        String flat = content.strip().replaceAll("\\s+", " ");
        int idx = flat.toLowerCase().indexOf(query.toLowerCase());
        if (idx < 0) {
            // Запрос с пробелами мог совпасть в сыром тексте через перенос строки — после
            // схлопывания его не найти; показываем начало сообщения.
            int cap = SNIPPET_PREFIX + SNIPPET_SUFFIX;
            return flat.length() > cap ? flat.substring(0, cap) + "…" : flat;
        }
        int start = Math.max(0, idx - SNIPPET_PREFIX);
        int end = Math.min(flat.length(), idx + query.length() + SNIPPET_SUFFIX);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < flat.length() ? "…" : "";
        return prefix + flat.substring(start, end).strip() + suffix;
    }
}
