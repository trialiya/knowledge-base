package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

/**
 * Вызовы инструментов внутри истории чата: индекс {@code tool_call_index}, UI-метаданные плашек и
 * полные детали одного вызова для модалки.
 *
 * <p>Протокольные {@code tool_data} сюда не пишутся — они часть самого сообщения и оседают вместе с
 * ним в {@link ChatHistoryService#append}. Здесь всё, что нужно поверх них: чем вызов был для
 * пользователя и как найти его, не сканируя историю.
 */
@AllArgsConstructor
@Service
public class ToolCallService {

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

    /** Гист результата в live-событии — как у строкового результата в RecordingToolCallback. */
    static final int RESULT_GIST_MAX = 50;

    private final ChatMessageRepository chatMessageRepository;
    private final ToolCallIndexRepository toolCallIndexRepository;

    /** Возвращает {@code true}, если детали вызова инструмента сохраняются в БД. */
    static boolean hasDetails(String toolName) {
        return !SKIP_TOOLS.contains(toolName);
    }

    /**
     * Наполняет {@code tool_call_index} сразу при персисте нового сегмента: id вызова уже лежит в
     * {@code tool_data} (в отличие от UI-меты — status/error/resultMeta, — для этого не нужно
     * позиционное сопоставление с коллектором, см. {@link #runInvocations}). Два прохода по только
     * что сохранённым рядам: сперва вставляем строки по tool_calls ASSISTANT-сегментов, затем
     * проставляем {@code responseMessageId} по responses TOOL-сообщений — так пара «вызов и ответ в
     * одном {@code append}» тоже отрабатывает корректно (вставка гарантированно раньше обновления).
     */
    void index(String conversationId, List<ChatMessageEntity> saved) {
        final List<ToolCallIndexEntity> newRows = new ArrayList<>();
        for (ChatMessageEntity row : saved) {
            final ToolData toolData = row.getToolData();
            if (toolData == null || toolData.toolCalls() == null) {
                continue;
            }
            for (ToolData.Call call : toolData.toolCalls()) {
                final ToolCallIndexEntity indexRow = new ToolCallIndexEntity();
                indexRow.setConversationId(conversationId);
                indexRow.setCallId(call.id());
                indexRow.setMessageId(row.getId());
                newRows.add(indexRow);
            }
        }
        if (!newRows.isEmpty()) {
            toolCallIndexRepository.saveAll(newRows);
        }
        for (ChatMessageEntity row : saved) {
            final ToolData toolData = row.getToolData();
            if (toolData == null || toolData.responses() == null) {
                continue;
            }
            for (ToolData.Response response : toolData.responses()) {
                toolCallIndexRepository.setResponseMessageId(
                        conversationId, response.id(), row.getId());
            }
        }
    }

    /**
     * Полные детали одного вызова инструмента — точечно по {@code callId}, без скана истории чата:
     * {@code tool_call_index} даёт messageId сегмента и (если уже пришёл ответ) responseMessageId
     * TOOL-строки одним запросом, дальше обе строки достаются одним {@code findAllById} по
     * первичному ключу. {@code callId} — протокольный id вызова — выбирает нужную запись из {@code
     * tool_data.toolCalls}/{@code .responses} каждой строки (в сегменте/TOOL-строке их может быть
     * несколько). UI-мета (status/error/resultMeta) берётся из {@code meta.invocations} сегмента по
     * тому же {@code callId}. {@code conversationId} сверяется у обеих строк — защита от
     * подстановки чужого id.
     *
     * <p>Работает и на ещё не завершённом вызове: ASSISTANT-сегмент с аргументами сохранён и
     * проиндексирован до того, как инструмент начал работу (см. {@link ToolCallEventPublisher}), —
     * тогда деталь несёт аргументы, статус STARTED и пустой результат.
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
                        // call==null && invocation==null already returned above, so if
                        // invocation is null here, call is not.
                        invocation != null
                                ? invocation.name()
                                : Objects.requireNonNull(call).name(),
                        call != null ? call.arguments() : null,
                        // Мета вызова появляется только в конце прогона (markRunResult), а
                        // аргументы лежат в сегменте с самого его персиста — модалка деталей
                        // открывается и на ещё работающем вызове. Пока ответа нет, статус —
                        // STARTED, иначе идущий вызов показался бы успешно завершённым; ответ
                        // без меты — оборванный прогон, он и правда отработал.
                        invocation != null
                                ? invocation.status()
                                : resultText != null
                                        ? ToolInvocationStatus.OK
                                        : ToolInvocationStatus.STARTED,
                        invocation != null ? invocation.error() : null,
                        resultText,
                        invocation != null ? invocation.resultMeta() : null,
                        segment.getCreatedAt()));
    }

    /**
     * Метаданные плашек вызовов для сегмента: сохранённые {@code meta.invocations}, а если их нет
     * (прогон оборвался до записи меты, старые данные) — синтезированные из {@code tool_data}: имя
     * и усечённые аргументы из toolCalls сегмента, гист — из ответа в TOOL-сообщениях среди {@code
     * context} (строк той же страницы). Статус всегда OK (история = завершённые вызовы),
     * hasDetails=false — намеренно не предлагаем детали для этого синтезированного (не через {@link
     * #runInvocations}) пути. {@code SKIP_TOOLS} вырезаны, как и там.
     */
    public @Nullable List<ToolInvocationMeta> invocationsFor(
            ChatMessageEntity entity, List<ChatMessageEntity> context) {
        final List<ToolInvocationMeta> stored = entity.getInvocations();
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        // Условие — «плашек нет», а не «меты нет»: мета есть у каждого ответа прогона (в ней
        // модель, см. ChatHistoryService#markRunResult), и сегмент с сохранёнными tool_calls, но
        // без плашек — это как раз оборванный прогон, ради которого синтез и нужен.
        if (entity.getType() != MessageType.ASSISTANT
                || entity.getToolData() == null
                || entity.getToolData().toolCalls() == null) {
            return stored;
        }
        // Сегменту из одних SKIP_TOOLS синтезировать нечего (фильтр ниже всё равно всё вырежет) —
        // не сканируем ради него строки страницы на каждый показ.
        if (entity.getToolData().toolCalls().stream().noneMatch(call -> hasDetails(call.name()))) {
            return stored;
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
                .filter(call -> hasDetails(call.name()))
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
     * Раскладывает вызовы прогона по его ASSISTANT-сегментам — тем, что advisor-цепочка уже
     * сохранила с протокольными tool_calls (см. {@link ChatHistoryService#append}). Сегменты идут в
     * порядке позиций, каждый потребляет из общего списка вызовов столько, сколько у него
     * tool_calls — список вызовов прогона хронологический, поэтому сопоставление однозначно; та же
     * позиционная связь даёт callId каждого вызова (у {@link ToolInvocation} самого протокольного
     * id нет — {@code RecordingToolCallback} его не видит). messageId/responseMessageId сюда не
     * тянем — {@link #index} наполняет ими {@code tool_call_index} напрямую по callId, без
     * позиционной арифметики.
     *
     * <p>Чистая функция: мету рядам пишет {@link ChatHistoryService#markRunResult} — одним заходом
     * вместе с моделью и токенами прогона. Порознь это было бы парой с негласным порядком: плашки
     * ищутся по {@code meta == null}, и проставленная раньше модель прятала бы от них сегменты.
     *
     * <p>{@code SKIP_TOOLS} вырезаются только из UI-метаданных — протокольные tool_calls сегмента
     * остаются полными, иначе модель получила бы рассинхронизированную пару tool-сообщений.
     *
     * @param segments необогащённые ({@code meta == null}) ASSISTANT-ряды хода, в порядке позиций
     * @param toolCalls вызовы прогона в хронологическом порядке (снимок коллектора)
     * @return плашки по id сегмента, в том же порядке; сегменты без вызовов в карту не попадают
     */
    static Map<Long, List<ToolInvocationMeta>> runInvocations(
            List<ChatMessageEntity> segments, List<ToolInvocation> toolCalls) {
        if (toolCalls.isEmpty()) {
            return Map.of();
        }
        final Map<Long, List<ToolInvocationMeta>> bySegment = new LinkedHashMap<>();
        int cursor = 0;
        for (ChatMessageEntity segment : segments) {
            final ToolData toolData = segment.getToolData();
            if (toolData == null
                    || toolData.toolCalls() == null
                    || toolData.toolCalls().isEmpty()) {
                continue;
            }
            final List<ToolData.Call> segmentCalls = toolData.toolCalls();
            final int end = Math.min(cursor + segmentCalls.size(), toolCalls.size());
            if (cursor >= end) {
                break;
            }
            final List<ToolInvocationMeta> metas = new ArrayList<>();
            for (int i = cursor; i < end; i++) {
                final ToolInvocation tc = toolCalls.get(i);
                if (!hasDetails(tc.name())) {
                    continue;
                }
                final ToolData.Call call = segmentCalls.get(i - cursor);
                metas.add(tc.toMeta(true, call.id()));
            }
            cursor = end;
            bySegment.put(segment.getId(), metas);
        }
        return bySegment;
    }
}
