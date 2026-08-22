package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Live-события TOOL_CALL текущего прогона — по только что сохранённым рядам (см. {@link
 * ChatHistoryService#append}): STARTED по tool_calls нового ASSISTANT-сегмента (имя и аргументы уже
 * известны), OK по responses нового TOOL-сообщения. Событие уходит только после персиста, то есть
 * фронт не увидит вызова, которого нет в БД. Ошибки и resultMeta здесь недоступны (в {@link
 * ToolData} только сырой текст) — их доносит финальное TOOL_CALLS-событие прогона (см. {@code
 * ChatRunService.onComplete}).
 *
 * <p>Событие несёт протокольный {@code callId} — модалка деталей находит
 * messageId/responseMessageId сама через {@code tool_call_index} (см. {@link
 * ToolCallService#findToolCallDetail}), точечным запросом вместо скана истории. {@code callIndex}
 * нужен фронту, чтобы склеить живую плашку с итоговой метой того же вызова, поэтому считается он
 * так же, как в {@code ToolInvocationCollector}: сквозной счётчик вызовов прогона, включая те, что
 * в UI не показываются ({@code SKIP_TOOLS} занимают номер, но события не порождают). Отсюда и
 * состояние на чат ниже — без него номера пришлось бы каждый раз восстанавливать сканом хвоста
 * истории, а он для этого ненадёжен: после повтора упавшего прогона в хвосте лежат ещё и его
 * сегменты, которых счётчик коллектора не видел.
 *
 * <p>Без активного прогона (синхронный путь, ремонт хвоста) события не шлются.
 */
@AllArgsConstructor
@Service
public class ToolCallEventPublisher {

    private final ChatEventService events;

    /**
     * Нумерация вызовов текущего прогона чата. Прогоны на чат строго последовательны, а состояние
     * живёт до конца прогона — записи ASSISTANT-сегмента (вызовы) и TOOL-ответа приходят разными
     * {@code append}, и второму нужны номера, розданные первым.
     *
     * <p>Снимается по {@link #forget} на завершении прогона: в записях лежат разобранные аргументы
     * всех его вызовов, и держать их до следующего прогона этого чата (который может не случиться
     * никогда) — течь размером в историю инструментов на каждый когда-либо открытый чат.
     */
    private final Map<String, RunCalls> byConversation = new ConcurrentHashMap<>();

    /** Уже пронумерованные вызовы прогона: номер и аргументы, которыми потом дополняется ответ. */
    private static final class RunCalls {
        private final String runId;
        private final Map<String, Started> byCallId = new HashMap<>();
        private int next;

        private RunCalls(String runId) {
            this.runId = runId;
        }
    }

    private record Started(int callIndex, Map<Object, Object> arguments) {}

    public void publish(String conversationId, List<ChatMessageEntity> saved) {
        final Optional<String> runId = events.activeRunId(conversationId);
        if (runId.isEmpty()) {
            byConversation.remove(conversationId);
            return;
        }
        final RunCalls calls =
                byConversation.compute(
                        conversationId,
                        (id, existing) ->
                                existing != null && existing.runId.equals(runId.get())
                                        ? existing
                                        : new RunCalls(runId.get()));
        synchronized (calls) {
            for (ChatMessageEntity row : saved) {
                publishRow(conversationId, runId.get(), row, calls);
            }
        }
    }

    private void publishRow(
            String conversationId, String runId, ChatMessageEntity row, RunCalls calls) {
        final ToolData toolData = row.getToolData();
        if (toolData == null) {
            return;
        }
        if (toolData.toolCalls() != null) {
            for (ToolData.Call call : toolData.toolCalls()) {
                final Started started =
                        new Started(
                                calls.next++,
                                RecordingToolCallback.parseToolInput(call.arguments()));
                calls.byCallId.put(call.id(), started);
                // SKIP_TOOLS не показываем нигде: ни live, ни после перезагрузки
                // (attachRunMeta их тоже вырезает); номер при этом занимают — он должен
                // совпадать со счётчиком коллектора.
                if (ToolCallService.hasDetails(call.name())) {
                    publish(
                            conversationId,
                            runId,
                            new ToolInvocationMeta(
                                    call.name(),
                                    started.arguments(),
                                    ToolInvocationStatus.STARTED,
                                    null,
                                    null,
                                    true,
                                    started.callIndex(),
                                    null,
                                    call.id()));
                }
            }
        }
        if (toolData.responses() != null) {
            for (ToolData.Response response : toolData.responses()) {
                if (!ToolCallService.hasDetails(response.name())) {
                    continue;
                }
                final Started started = calls.byCallId.get(response.id());
                publish(
                        conversationId,
                        runId,
                        new ToolInvocationMeta(
                                response.name(),
                                started != null ? started.arguments() : Map.of(),
                                ToolInvocationStatus.OK,
                                null,
                                null,
                                true,
                                started != null ? started.callIndex() : null,
                                Compact.truncate(
                                        response.responseData(), ToolCallService.RESULT_GIST_MAX),
                                response.id()));
            }
        }
    }

    /**
     * Прогон чата закончился — нумерация его вызовов больше не нужна. Зовётся из {@code
     * ChatRunService.cleanup}, то есть и на успехе, и на остановке, и на ошибке.
     */
    public void forget(String conversationId) {
        byConversation.remove(conversationId);
    }

    /** Сколько чатов держат нумерацию вызовов — для мониторинга утечек (см. ChatRuntimeMonitor). */
    public int trackedConversationCount() {
        return byConversation.size();
    }

    private void publish(String conversationId, String runId, ToolInvocationMeta meta) {
        events.publish(
                conversationId, ChatEventType.TOOL_CALL, runId, null, new ToolCallMessage(meta));
    }
}
