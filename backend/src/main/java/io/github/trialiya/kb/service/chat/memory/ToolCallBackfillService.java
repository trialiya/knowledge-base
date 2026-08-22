package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Разовый бэкафилл данных, записанных до появления {@code tool_call_index} и {@code
 * ToolInvocationMeta#callId}. Не развивать: как только все окружения отработают его, класс уходит
 * вместе с {@link ToolCallIdBackfillRunner} и маркером в {@code backfill_state}.
 */
@AllArgsConstructor
@Service
public class ToolCallBackfillService {

    /**
     * Ключ run-once маркера в {@code backfill_state} (см. {@link #backfillToolCallIdsIfNeeded}).
     */
    public static final String TOOL_CALL_ID_BACKFILL_KEY = "tool-call-id-backfill";

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ToolCallIndexRepository toolCallIndexRepository;
    private final BackfillStateRepository backfillStateRepository;

    /**
     * Run-once точка входа (см. {@link ToolCallIdBackfillRunner}): если в {@code backfill_state}
     * уже есть маркер {@value #TOOL_CALL_ID_BACKFILL_KEY} — no-op, иначе выполняет {@link
     * #backfillToolCallIds()} и сохраняет маркер в той же транзакции. Повторные старты не сканируют
     * историю заново; флага конфигурации нет.
     */
    @Transactional
    public BackfillResult backfillToolCallIdsIfNeeded() {
        if (backfillStateRepository.existsById(TOOL_CALL_ID_BACKFILL_KEY)) {
            return new BackfillResult(0, 0);
        }
        final BackfillResult result = backfillToolCallIds();
        backfillStateRepository.save(
                new BackfillStateEntity(TOOL_CALL_ID_BACKFILL_KEY, LocalDateTime.now(), true));
        return result;
    }

    /**
     * Две независимые части:
     *
     * <ol>
     *   <li>{@code tool_call_index} наполняется по всем {@code chat_message.tool_data} разговора —
     *       без какой-либо позиционной арифметики: протокольный id вызова уже лежит в самих данных
     *       (см. {@link #indexToolCallsFromHistory}).
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
}
