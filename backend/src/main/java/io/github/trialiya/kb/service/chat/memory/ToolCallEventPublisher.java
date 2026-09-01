package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.service.chat.runtime.RunScope;
import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Live-события TOOL_CALL текущего прогона — по только что сохранённым рядам (см. {@link
 * ChatHistoryService#append}): STARTED по tool_calls нового ASSISTANT-сегмента (имя и аргументы уже
 * известны), OK или ERROR по responses нового TOOL-сообщения. Событие уходит только после персиста,
 * то есть фронт не увидит вызова, которого нет в БД. Провал виден не по самому ответу — в нём на
 * месте результата лежит текст ошибки, — а по записи коллектора ({@link RunScope#completedCall}).
 * resultMeta здесь недоступна (в {@link ToolData} только сырой текст) — её доносит финальное
 * TOOL_CALLS-событие прогона (см. {@code ChatRunService.onComplete}).
 *
 * <p>Событие несёт протокольный {@code callId} — модалка деталей находит
 * messageId/responseMessageId сама через {@code tool_call_index} (см. {@link
 * ToolCallService#findToolCallDetail}), точечным запросом вместо скана истории. {@code callIndex}
 * нужен фронту, чтобы склеить живую плашку с итоговой метой того же вызова, поэтому считается он
 * так же, как в {@code ToolInvocationCollector}: сквозной счётчик вызовов прогона, включая те, что
 * в UI не показываются ({@code SKIP_TOOLS} занимают номер, но события не порождают). Живёт счётчик
 * в области прогона ({@link RunScope}) — восстанавливать номер сканом хвоста истории нельзя: после
 * повтора упавшего прогона в хвосте лежат ещё и его сегменты, которых счётчик коллектора не видел.
 *
 * <p>Без идущего прогона (синхронный путь, ремонт хвоста, сжатие контекста) события не шлются:
 * области нет — нумеровать нечем и склеивать нечего.
 */
@AllArgsConstructor
@Service
public class ToolCallEventPublisher {

    private final ChatEventService events;
    private final RunRegistry runs;

    public void publish(String conversationId, List<ChatMessageEntity> saved) {
        // Область прогона — и есть проверка «прогон ещё жив»: заводит и закрывает её только
        // владелец, поэтому опоздавшая запись ничего не воскрешает, а просто ничего не находит.
        final Optional<RunScope> scope = events.activeRunId(conversationId).flatMap(runs::find);
        if (scope.isEmpty()) {
            return;
        }
        for (ChatMessageEntity row : saved) {
            publishRow(conversationId, scope.get(), row);
        }
    }

    private void publishRow(String conversationId, RunScope scope, ChatMessageEntity row) {
        final ToolData toolData = row.getToolData();
        if (toolData == null) {
            return;
        }
        if (toolData.toolCalls() != null) {
            for (ToolData.Call call : toolData.toolCalls()) {
                // SKIP_TOOLS не показываем нигде: ни live, ни после перезагрузки
                // (markRunResult их тоже вырезает); номер при этом занимают — он должен
                // совпадать со счётчиком коллектора. Запоминать их не нужно: запомненное
                // читает только ветка ответов ниже, а она SKIP_TOOLS отбрасывает.
                final int callIndex = scope.nextCallIndex();
                if (!ToolCallService.hasDetails(call.name())) {
                    continue;
                }
                final Map<Object, Object> arguments =
                        RecordingToolCallback.parseToolInput(call.arguments());
                scope.rememberCall(call.id(), callIndex, arguments);
                publish(
                        conversationId,
                        scope.runId(),
                        new ToolInvocationMeta(
                                call.name(),
                                arguments,
                                ToolInvocationStatus.STARTED,
                                null,
                                null,
                                true,
                                callIndex,
                                null,
                                call.id()));
            }
        }
        if (toolData.responses() != null) {
            for (ToolData.Response response : toolData.responses()) {
                if (!ToolCallService.hasDetails(response.name())) {
                    continue;
                }
                final RunScope.StartedCall started = scope.startedCall(response.id());
                if (started == null) {
                    // Вызова прогон не нумеровал (STARTED этой пары ушёл в другом прогоне) — OK
                    // без номера и аргументов фронт склеить не сможет, событие с пустыми данными
                    // хуже его отсутствия: правильную плашку всё равно принесёт финальное
                    // TOOL_CALLS (markRunResult) или reload.
                    continue;
                }
                // Исход — у коллектора: сам ответ о нём молчит, в нём у провалившегося вызова
                // лежит текст ошибки на месте результата. Без этого вопроса плашка стояла бы
                // зелёной до конца прогона — до финального TOOL_CALLS, который один и приносил
                // статусы, — а после перезагрузки та же плашка краснела.
                final ToolInvocation outcome = scope.completedCall(started.callIndex());
                final ToolInvocation failure =
                        outcome != null && ToolInvocationStatus.ERROR == outcome.status()
                                ? outcome
                                : null;
                publish(
                        conversationId,
                        scope.runId(),
                        new ToolInvocationMeta(
                                response.name(),
                                started.arguments(),
                                failure != null
                                        ? ToolInvocationStatus.ERROR
                                        : ToolInvocationStatus.OK,
                                failure != null ? failure.error() : null,
                                null,
                                true,
                                started.callIndex(),
                                // У провала гиста нет: результат — та же ошибка, и плашка написала
                                // бы её дважды. Итоговая мета его тоже не несёт, так что склейка
                                // на фронте показанного не меняет.
                                failure != null
                                        ? null
                                        : Compact.truncate(
                                                response.responseData(),
                                                ToolCallService.RESULT_GIST_MAX),
                                response.id()));
            }
        }
    }

    private void publish(String conversationId, String runId, ToolInvocationMeta meta) {
        events.publish(
                conversationId, ChatEventType.TOOL_CALL, runId, null, new ToolCallMessage(meta));
    }
}
