package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    /** Гист результата в live-событии — как у строкового результата в RecordingToolCallback. */
    private static final int RESULT_GIST_MAX = 50;

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatEventService events;
    private final ToolCallIndexRepository toolCallIndexRepository;

    /** Сообщение + его протокольные tool-данные, извлечённые один раз (см. toolDataOf). */
    private record Pending(Message message, @Nullable ToolData toolData) {}

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMessageRepository.deleteChatMessageByConversationId(conversationId);
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        final AtomicLong lastPosition = new AtomicLong();
        final List<Pending> pending =
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
                                p ->
                                        Strings.isNotBlank(p.message().getText())
                                                || p.toolData() != null)
                        .toList();
        final List<ChatMessageEntity> newMessagesToSave =
                pending.stream()
                        .map(
                                p ->
                                        new ChatMessageEntity(
                                                0,
                                                conversationId,
                                                p.message().getText() == null
                                                        ? ""
                                                        : p.message().getText(),
                                                p.message().getMessageType(),
                                                lastPosition.incrementAndGet(),
                                                false,
                                                false,
                                                LocalDateTime.now(),
                                                null,
                                                p.toolData()))
                        .toList();
        final List<ChatMessageEntity> saved = new ArrayList<>();
        chatMessageRepository.saveAll(newMessagesToSave).forEach(saved::add);
        indexToolCalls(conversationId, pending, saved);
        publishToolCallEvents(conversationId, messages);
    }

    /**
     * Наполняет {@code tool_call_index} сразу при персисте нового сегмента: id вызова уже лежит в
     * {@code tool_data} (в отличие от UI-меты — status/error/resultMeta, — для этого не нужно
     * позиционное сопоставление с коллектором, см. {@link #attachRunMeta}). Два прохода по {@code
     * pending}: сперва вставляем строки по tool_calls новых ASSISTANT-сегментов (messageId уже
     * известен из {@code saved}), затем проставляем {@code responseMessageId} по responses новых
     * TOOL-сообщений — так пара «вызов в этом же saveAll, ответ в этом же saveAll» тоже
     * отрабатывает корректно (вставка гарантированно раньше обновления).
     */
    private void indexToolCalls(
            String conversationId, List<Pending> pending, List<ChatMessageEntity> saved) {
        final List<ToolCallIndexEntity> newRows = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            final ToolData toolData = pending.get(i).toolData();
            if (toolData == null || toolData.toolCalls() == null) {
                continue;
            }
            final long messageId = saved.get(i).getId();
            for (ToolData.Call call : toolData.toolCalls()) {
                final ToolCallIndexEntity row = new ToolCallIndexEntity();
                row.setConversationId(conversationId);
                row.setCallId(call.id());
                row.setMessageId(messageId);
                newRows.add(row);
            }
        }
        if (!newRows.isEmpty()) {
            toolCallIndexRepository.saveAll(newRows);
        }
        for (int i = 0; i < pending.size(); i++) {
            final ToolData toolData = pending.get(i).toolData();
            if (toolData == null || toolData.responses() == null) {
                continue;
            }
            final long messageId = saved.get(i).getId();
            for (ToolData.Response response : toolData.responses()) {
                toolCallIndexRepository.setResponseMessageId(
                        conversationId, response.id(), messageId);
            }
        }
    }

    /**
     * Live-события TOOL_CALL текущего прогона — из только что сохранённых tool-данных: STARTED — по
     * tool_calls нового ASSISTANT-сегмента (имя и аргументы уже известны), OK — по responses нового
     * TOOL-сообщения. Событие уходит только после персиста, т.е. фронт не увидит вызов, которого
     * нет в БД. Ошибки и resultMeta здесь недоступны (в {@link ToolData} только сырой текст) — их
     * доносит финальное TOOL_CALLS-событие прогона (см. ChatRunService.onComplete).
     *
     * <p>callIndex сквозной по прогону и равен позиции вызова среди tool_calls всех
     * ASSISTANT-сегментов после последнего USER-сообщения — те же допущения, что в {@link
     * #attachRunMeta}. Событие несёт только протокольный {@code callId} — модалка деталей находит
     * messageId/responseMessageId сама через {@code tool_call_index} (см. {@link
     * #findToolCallDetail}), точечным запросом по id вместо скана истории. Без активного прогона
     * (синхронный путь, ремонт хвоста) события не шлём.
     */
    private void publishToolCallEvents(String conversationId, List<Message> messages) {
        final Optional<String> runId = events.activeRunId(conversationId);
        if (runId.isEmpty()) {
            return;
        }
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageType() == MessageType.USER) {
                lastUser = i;
            }
        }
        int callIndex = 0;
        final Map<String, Integer> indexById = new HashMap<>();
        final Map<String, ToolData.Call> callById = new HashMap<>();
        for (int i = lastUser + 1; i < messages.size(); i++) {
            final Message message = messages.get(i);
            final boolean alreadySaved = message instanceof IMessage;
            final ToolData toolData =
                    alreadySaved
                            ? ((IMessage) message).chatMessage().getToolData()
                            : toolDataOf(message);
            if (toolData == null) {
                continue;
            }
            if (toolData.toolCalls() != null) {
                for (ToolData.Call call : toolData.toolCalls()) {
                    indexById.put(call.id(), callIndex);
                    callById.put(call.id(), call);
                    // SKIP_TOOLS не показываем нигде: ни live, ни после перезагрузки
                    // (attachRunMeta их тоже вырезает); callIndex при этом считает их —
                    // он должен совпадать с позицией в toolCalls.
                    if (!alreadySaved && hasDetails(call.name())) {
                        publishToolCall(
                                conversationId,
                                runId.get(),
                                new ToolInvocationMeta(
                                        call.name(),
                                        RecordingToolCallback.parseToolInput(call.arguments()),
                                        ToolInvocationStatus.STARTED,
                                        null,
                                        null,
                                        hasDetails(call.name()),
                                        callIndex,
                                        null,
                                        call.id()));
                    }
                    callIndex++;
                }
            }
            if (!alreadySaved && toolData.responses() != null) {
                for (ToolData.Response response : toolData.responses()) {
                    if (!hasDetails(response.name())) {
                        continue;
                    }
                    final ToolData.Call call = callById.get(response.id());
                    publishToolCall(
                            conversationId,
                            runId.get(),
                            new ToolInvocationMeta(
                                    response.name(),
                                    call != null
                                            ? RecordingToolCallback.parseToolInput(call.arguments())
                                            : Map.of(),
                                    ToolInvocationStatus.OK,
                                    null,
                                    null,
                                    hasDetails(response.name()),
                                    indexById.get(response.id()),
                                    Compact.truncate(response.responseData(), RESULT_GIST_MAX),
                                    response.id()));
                }
            }
        }
    }

    private void publishToolCall(String conversationId, String runId, ToolInvocationMeta meta) {
        events.publish(
                conversationId, ChatEventType.TOOL_CALL, runId, null, new ToolCallMessage(meta));
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
     * Полные детали одного вызова инструмента — точечно по {@code callId}, без скана истории чата:
     * {@code tool_call_index} даёт messageId сегмента и (если уже пришёл ответ) responseMessageId
     * TOOL-строки одним запросом, дальше обе строки достаются одним {@code findAllById} по
     * первичному ключу. {@code callId} — протокольный id вызова — выбирает нужную запись из {@code
     * tool_data.toolCalls}/{@code .responses} каждой строки (в сегменте/TOOL-строке их может быть
     * несколько). UI-мета (status/error/resultMeta) берётся из {@code meta.invocations} сегмента по
     * тому же {@code callId}. {@code conversationId} сверяется у обеих строк — защита от
     * подстановки чужого id.
     */
    public Optional<ToolCallDetail> findToolCallDetail(String conversationId, String callId) {
        final Optional<ToolCallIndexEntity> indexed =
                toolCallIndexRepository.findByConversationIdAndCallId(conversationId, callId);
        if (indexed.isEmpty()) {
            return Optional.empty();
        }
        final long messageId = indexed.get().getMessageId();
        final Long responseMessageId = indexed.get().getResponseMessageId();
        final List<Long> ids =
                responseMessageId == null
                        ? List.of(messageId)
                        : List.of(messageId, responseMessageId);
        final Map<Long, ChatMessageEntity> byId = new HashMap<>();
        chatMessageRepository
                .findAllById(ids)
                .forEach(
                        e -> {
                            if (conversationId.equals(e.getConversationId())) {
                                byId.put(e.getId(), e);
                            }
                        });
        final ChatMessageEntity segment = byId.get(messageId);
        if (segment == null || segment.getType() != MessageType.ASSISTANT) {
            return Optional.empty();
        }
        final ToolData.Call call =
                segment.getToolData() != null && segment.getToolData().toolCalls() != null
                        ? segment.getToolData().toolCalls().stream()
                                .filter(c -> callId.equals(c.id()))
                                .findFirst()
                                .orElse(null)
                        : null;
        final ToolInvocationMeta invocation =
                segment.getMeta() != null
                        ? segment.getMeta().invocations().stream()
                                .filter(inv -> callId.equals(inv.callId()))
                                .findFirst()
                                .orElse(null)
                        : null;
        if (call == null && invocation == null) {
            return Optional.empty();
        }
        final ChatMessageEntity responseRow =
                responseMessageId != null ? byId.get(responseMessageId) : null;
        final String resultText =
                responseRow != null
                                && responseRow.getToolData() != null
                                && responseRow.getToolData().responses() != null
                        ? responseRow.getToolData().responses().stream()
                                .filter(r -> callId.equals(r.id()))
                                .map(ToolData.Response::responseData)
                                .findFirst()
                                .orElse(null)
                        : null;
        return Optional.of(
                new ToolCallDetail(
                        invocation != null ? invocation.name() : call.name(),
                        call != null ? call.arguments() : null,
                        invocation != null ? invocation.status() : ToolInvocationStatus.OK,
                        invocation != null ? invocation.error() : null,
                        resultText,
                        invocation != null ? invocation.resultMeta() : null,
                        segment.getCreatedAt()));
    }

    /**
     * Метаданные плашек вызовов для сегмента: сохранённые {@code meta.invocations}, а если их нет
     * (прогон оборвался до {@link #attachRunMeta}, старые данные) — синтезированные из {@code
     * tool_data}: имя и усечённые аргументы из toolCalls сегмента, гист — из ответа в
     * TOOL-сообщениях среди {@code context} (строк той же страницы). Статус всегда OK (история =
     * завершённые вызовы), hasDetails=false — намеренно не предлагаем детали для этого
     * синтезированного (не через {@link #attachRunMeta}) пути. {@code SKIP_TOOLS} вырезаны, как и в
     * {@link #attachRunMeta}.
     */
    public @Nullable List<ToolInvocationMeta> invocationsFor(
            ChatMessageEntity entity, List<ChatMessageEntity> context) {
        if (entity.getInvocations() != null) {
            return entity.getInvocations();
        }
        if (entity.getMeta() != null
                || entity.getType() != MessageType.ASSISTANT
                || entity.getToolData() == null
                || entity.getToolData().toolCalls() == null) {
            return null;
        }
        final Map<String, String> responseById = new HashMap<>();
        for (ChatMessageEntity row : context) {
            if (row.getType() == MessageType.TOOL
                    && row.getToolData() != null
                    && row.getToolData().responses() != null) {
                for (ToolData.Response response : row.getToolData().responses()) {
                    responseById.put(response.id(), response.responseData());
                }
            }
        }
        return entity.getToolData().toolCalls().stream()
                .filter(call -> !SKIP_TOOLS.contains(call.name()))
                .map(
                        call ->
                                new ToolInvocationMeta(
                                        call.name(),
                                        RecordingToolCallback.parseToolInput(call.arguments()),
                                        ToolInvocationStatus.OK,
                                        null,
                                        null,
                                        false,
                                        null,
                                        Compact.truncate(
                                                responseById.get(call.id()), RESULT_GIST_MAX),
                                        null))
                .toList();
    }

    /**
     * Прикрепляет UI-метаданные вызовов инструментов к ASSISTANT-сегментам прогона, которые
     * advisor-цепочка уже сохранила с протокольными tool_calls (см. {@link #saveAll}). Сегменты
     * идут в порядке позиций, каждый потребляет из общего списка вызовов столько, сколько у него
     * tool_calls — список вызовов прогона хронологический, поэтому сопоставление однозначно; та же
     * позиционная связь даёт callId каждого вызова (у {@link ToolInvocation} самого протокольного
     * id нет — {@code RecordingToolCallback} его не видит). messageId/responseMessageId сюда уже не
     * тянем — {@link #saveAll} наполняет ими {@code tool_call_index} напрямую по callId, без
     * позиционной арифметики.
     *
     * <p>Рассматриваются только сегменты после последнего USER-сообщения: прогоны на чат строго
     * последовательны, значит всё, что после последнего вопроса, относится к текущему прогону, а
     * необогащённые сегменты старых аварийных прогонов не перехватят чужие вызовы.
     *
     * <p>{@code SKIP_TOOLS} вырезаются только из UI-метаданных — протокольные tool_calls сегмента
     * остаются полными, иначе модель получила бы рассинхронизированную пару tool-сообщений.
     *
     * @return сохранённые метаданные всех вызовов прогона (без SKIP_TOOLS), в хронологическом
     *     порядке — используется для финального live-события TOOL_CALLS (см. ChatRunService), чтобы
     *     не пересчитывать то же самое дважды.
     */
    @Transactional
    public List<ToolInvocationMeta> attachRunMeta(
            String conversationId, String runId, @Nullable List<ToolInvocation> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
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
        final List<ChatMessageEntity> tail = all.subList(lastUser + 1, all.size());
        final List<ChatMessageEntity> segments =
                tail.stream()
                        .filter(e -> e.getType() == MessageType.ASSISTANT)
                        .filter(e -> e.getMeta() == null)
                        .filter(
                                e ->
                                        e.getToolData() != null
                                                && e.getToolData().toolCalls() != null
                                                && !e.getToolData().toolCalls().isEmpty())
                        .toList();
        final List<ToolInvocationMeta> allMetas = new ArrayList<>();
        int cursor = 0;
        for (ChatMessageEntity segment : segments) {
            final List<ToolData.Call> segmentCalls = segment.getToolData().toolCalls();
            final int end = Math.min(cursor + segmentCalls.size(), toolCalls.size());
            if (cursor >= end) {
                break;
            }
            final List<ToolInvocationMeta> metas = new ArrayList<>();
            for (int i = cursor; i < end; i++) {
                final ToolInvocation tc = toolCalls.get(i);
                if (SKIP_TOOLS.contains(tc.name())) {
                    continue;
                }
                final ToolData.Call call = segmentCalls.get(i - cursor);
                metas.add(tc.toMeta(hasDetails(tc.name()), call.id()));
            }
            cursor = end;
            chatMessageRepository.save(segment.withMeta(new ChatMessageMeta(runId, false, metas)));
            allMetas.addAll(metas);
        }
        return allMetas;
    }

    /**
     * Одноразовый бэкафилл для данных, сохранённых до появления {@code tool_call_index}/{@code
     * ToolInvocationMeta#callId}. Запускается вручную ({@code kb.backfill.tool-call-ids=true}, см.
     * {@code ToolCallIdBackfillRunner}). Две независимые части:
     *
     * <ol>
     *   <li>{@code tool_call_index} наполняется по всем {@code chat_message.tool_data} разговора —
     *       без какой-либо позиционной арифметики: протокольный id вызова уже лежит в самих данных
     *       (см. {@link #indexToolCallsFromHistory}), в отличие от того, что раньше делал этот
     *       метод.
     *   <li>Старые {@code meta.invocations} без {@code callId} дозаполняются им же — единственное
     *       место, где позиционное сопоставление всё ещё нужно: у {@link ToolInvocation} из
     *       коллектора протокольного id никогда не было, только порядковый {@code callIndex}.
     *       Позиция вызова в {@code tool_data.toolCalls} сегмента = {@code callIndex} записи минус
     *       offset — сумма {@code toolCalls.size()} по более ранним сегментам ТОГО ЖЕ прогона.
     * </ol>
     *
     * <p>Идемпотентно: и вставка в {@code tool_call_index}, и заполнение {@code callId} пропускают
     * уже заполненные записи, так что повторный прогон — дешёвый no-op.
     */
    @Transactional
    public BackfillResult backfillToolCallIds() {
        int conversationsTouched = 0;
        int invocationsFilled = 0;
        for (String conversationId : chatTopicRepository.findAllConversationIds()) {
            final List<ChatMessageEntity> all =
                    chatMessageRepository
                            .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                    conversationId);
            boolean touchedThisConversation = indexToolCallsFromHistory(conversationId, all);

            // offset — по прогону (runId): сегменты чужих прогонов вклад в него не дают.
            final Map<String, Integer> offsetByRunId = new HashMap<>();
            for (ChatMessageEntity segment : all) {
                if (segment.getType() != MessageType.ASSISTANT
                        || segment.getMeta() == null
                        || segment.getToolData() == null
                        || segment.getToolData().toolCalls() == null) {
                    continue;
                }
                final String runId = segment.getMeta().runId();
                final List<ToolData.Call> calls = segment.getToolData().toolCalls();
                final int offset = offsetByRunId.getOrDefault(runId, 0);
                offsetByRunId.put(runId, offset + calls.size());
                if (segment.getMeta().invocations().stream()
                        .noneMatch(inv -> inv.callId() == null)) {
                    continue; // уже заполнено (или пустой список) — идемпотентность
                }
                final List<ToolInvocationMeta> updated = new ArrayList<>();
                boolean touchedThisSegment = false;
                for (ToolInvocationMeta inv : segment.getMeta().invocations()) {
                    if (inv.callId() != null || inv.callIndex() == null) {
                        updated.add(inv);
                        continue;
                    }
                    final int position = inv.callIndex() - offset;
                    if (position < 0 || position >= calls.size()) {
                        updated.add(inv); // смещение не сошлось — не портим данные, пропускаем
                        continue;
                    }
                    updated.add(
                            new ToolInvocationMeta(
                                    inv.name(),
                                    inv.arguments(),
                                    inv.status(),
                                    inv.error(),
                                    inv.resultMeta(),
                                    inv.hasDetails(),
                                    inv.callIndex(),
                                    inv.resultGist(),
                                    calls.get(position).id()));
                    invocationsFilled++;
                    touchedThisSegment = true;
                }
                if (touchedThisSegment) {
                    chatMessageRepository.save(
                            segment.withMeta(
                                    new ChatMessageMeta(
                                            runId, segment.getMeta().toolCalls(), updated)));
                    touchedThisConversation = true;
                }
            }
            if (touchedThisConversation) {
                conversationsTouched++;
            }
        }
        return new BackfillResult(conversationsTouched, invocationsFilled);
    }

    /**
     * Наполняет {@code tool_call_index} по всей истории разговора: вставляет строки по {@code
     * tool_data.toolCalls} ASSISTANT-сегментов, которых там ещё нет, и проставляет {@code
     * responseMessageId} по {@code tool_data.responses} TOOL-сообщений. Идемпотентно — новые строки
     * определяются по уже присутствующим {@code callId}, обновление ответа само по себе не
     * затрагивает строки с уже верным значением (см. {@link
     * ToolCallIndexRepository#setResponseMessageId}).
     *
     * @return {@code true}, если что-то реально изменилось (для {@link BackfillResult})
     */
    private boolean indexToolCallsFromHistory(String conversationId, List<ChatMessageEntity> all) {
        final Set<String> existingCallIds = new HashSet<>();
        toolCallIndexRepository
                .findAllByConversationId(conversationId)
                .forEach(e -> existingCallIds.add(e.getCallId()));
        final List<ToolCallIndexEntity> newRows = new ArrayList<>();
        for (ChatMessageEntity row : all) {
            if (row.getType() != MessageType.ASSISTANT
                    || row.getToolData() == null
                    || row.getToolData().toolCalls() == null) {
                continue;
            }
            for (ToolData.Call call : row.getToolData().toolCalls()) {
                if (existingCallIds.add(call.id())) {
                    final ToolCallIndexEntity entity = new ToolCallIndexEntity();
                    entity.setConversationId(conversationId);
                    entity.setCallId(call.id());
                    entity.setMessageId(row.getId());
                    newRows.add(entity);
                }
            }
        }
        if (!newRows.isEmpty()) {
            toolCallIndexRepository.saveAll(newRows);
        }
        boolean touched = !newRows.isEmpty();
        for (ChatMessageEntity row : all) {
            if (row.getType() != MessageType.TOOL
                    || row.getToolData() == null
                    || row.getToolData().responses() == null) {
                continue;
            }
            for (ToolData.Response response : row.getToolData().responses()) {
                final int rows =
                        toolCallIndexRepository.setResponseMessageId(
                                conversationId, response.id(), row.getId());
                touched = touched || rows > 0;
            }
        }
        return touched;
    }

    public record BackfillResult(int conversationsTouched, int invocationsFilled) {}

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
