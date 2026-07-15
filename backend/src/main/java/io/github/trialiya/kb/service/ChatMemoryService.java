package io.github.trialiya.kb.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.tool.ToolCallEntity;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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

    private static final String PREAMBLE =
            """
        Инструменты, уже вызванные ранее в этом чате (с урезанным результатом).
        Служебные данные только для справки.
        """;

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMessageRepository.deleteChatMessageByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        final AtomicLong lastPosition = new AtomicLong();
        final List<ChatMessageEntity> newMessagesToSave =
                messages.stream()
                        .filter(message -> Strings.isNotBlank(message.getText()))
                        .filter(
                                message -> {
                                    if (message instanceof IMessage iMessage) {
                                        lastPosition.set(iMessage.chatMessage().getPosition());
                                        return false;
                                    }
                                    return true;
                                })
                        .map(
                                message ->
                                        new ChatMessageEntity(
                                                0,
                                                conversationId,
                                                message.getText(),
                                                message.getMessageType(),
                                                lastPosition.incrementAndGet(),
                                                false,
                                                false,
                                                LocalDateTime.now(),
                                                null))
                        .toList();
        chatMessageRepository.saveAll(newMessagesToSave);
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

    @Transactional
    public void saveToolCallIncremental(
            String conversationId, String runId, int callIndex, ToolInvocation tc) {
        if (SKIP_TOOLS.contains(tc.name())) {
            return;
        }
        try {
            String resultMetaJson =
                    tc.resultMeta() != null && !tc.resultMeta().isEmpty()
                            ? objectMapper.writeValueAsString(tc.resultMeta())
                            : null;
            toolCallRepository.save(
                    new ToolCallEntity(
                            conversationId,
                            runId,
                            callIndex,
                            tc.name(),
                            tc.argumentsRaw(),
                            tc.status(),
                            tc.error(),
                            tc.resultText(),
                            resultMetaJson));
        } catch (JsonProcessingException e) {
            log.warn(
                    "Failed to serialize result meta for tool call {} in {}",
                    tc.name(),
                    conversationId,
                    e);
        }
    }

    @Transactional
    public void saveToolCalls(
            String conversationId, String runId, @Nullable List<ToolInvocation> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        final List<ToolInvocation> filtered =
                toolCalls.stream().filter(tc -> !SKIP_TOOLS.contains(tc.name())).toList();
        if (filtered.isEmpty()) {
            return;
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(filtered);
        } catch (JsonProcessingException e) {
            // крошки некритичны — логируем и не ломаем ход
            log.warn("Failed to serialize tool calls for {}", conversationId, e);
            return;
        }

        final List<ToolInvocationMeta> meta = new ArrayList<>(filtered.size());
        for (ToolInvocation toolCall : filtered) {
            meta.add(toolCall.toMeta(true));
        }
        // Сохраняем «крошки» как ASSISTANT, а не SYSTEM: не все модели принимают системные
        // сообщения в середине диалога. Пользователю это сообщение по-прежнему не показываем —
        // его помечает флаг meta.toolCalls=true, по которому контроллер вырезает JSON
        // (см. toChatMessage), а фронт прячет пузырь (см. transformPage).
        chatMessageRepository.save(
                new ChatMessageEntity(
                        0L,
                        conversationId,
                        PREAMBLE + "\n" + json,
                        MessageType.ASSISTANT,
                        nextPosition(conversationId),
                        false,
                        false,
                        LocalDateTime.now(),
                        new ChatMessageMeta(runId, true, meta)));
    }

    private long nextPosition(String conversationId) {
        return chatMessageRepository
                        .findFirstByConversationIdOrderByPositionDesc(conversationId)
                        .map(ChatMessageEntity::getPosition)
                        .orElse(0L)
                + 1;
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
     * Сообщение видно пользователю и осмысленно для поиска: не служебное SYSTEM и не «крошка»
     * вызовов инструментов (см. {@link #saveToolCalls}) — те же критерии, что и в отображении
     * истории чата.
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
