package io.github.trialiya.kb.service.chat.run;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.MESSAGE_QUEUED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.USER_MESSAGE;

import io.github.trialiya.kb.model.chat.dto.QueuedMessagePayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatPendingMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.repository.ChatPendingMessageRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Очередь сообщений, отправленных в чат во время активного прогона, — единственный писатель и
 * читатель {@code chat_pending_message}. Принятое сообщение сразу ложится в БД (переживает и
 * падение процесса, и ошибку вызова модели), а в историю доставляется в ближайшем безопасном «окне»
 * — одной из трёх точек:
 *
 * <ul>
 *   <li>{@link #flushMidTurn} — между итерациями tool-цикла, когда ответы инструментов уже записаны
 *       (см. {@code InterjectionAdvisor}); ряд получает флаг {@code interjection};
 *   <li>{@link #flushPlain} из терминальной обработки прогона — окно так и не наступило (финальный
 *       ответ шёл без инструментов, прогон остановили или он упал): сообщение становится обычным
 *       вопросом — следующим ходом чата;
 *   <li>{@link #flushPlain} из восстановления после падения процесса — то же самое, только позже.
 * </ul>
 *
 * <p>Конкурирующие точки доставки (advisor и терминальная обработка могут прибежать почти
 * одновременно) разводятся claim-through-delete на уровне строки: доставляет тот, чей {@link
 * ChatPendingMessageRepository#claim} застал строку, — см. {@link #flush}.
 */
@AllArgsConstructor
@Slf4j
@Service
public class PendingMessageService {

    private final ChatPendingMessageRepository repository;
    private final ChatHistoryService chatHistory;
    private final ChatEventService events;

    /**
     * Снимок настроек прогона на момент отправки. Если доставка случится уже после завершения
     * текущего прогона, follow-up обязан поехать на них, а не на том, что окажется у чата к тому
     * моменту.
     */
    public record PendingOptions(
            @Nullable String model, @Nullable String mode, @Nullable String project) {}

    /**
     * Принимает сообщение в очередь чата и сообщает всем вкладкам ({@code MESSAGE_QUEUED}).
     * Проверку «прогон действительно активен» делает вызывающий — здесь только запись и событие.
     *
     * @param runId прогон, к которому сообщение встало в очередь, — едет в событие, чтобы вкладки
     *     отнесли «ожидающий» пузырь к правильному прогону
     * @param clientMsgId идентификатор вкладки-отправителя — гашение собственного эха, тот же
     *     смысл, что у {@code POST /runs}
     */
    @Transactional
    public ChatPendingMessageEntity enqueue(
            String conversationId,
            String user,
            String text,
            List<ContextItem> contextItems,
            PendingOptions options,
            String runId,
            @Nullable String clientMsgId) {
        final ChatPendingMessageEntity saved =
                repository.save(
                        new ChatPendingMessageEntity(
                                0,
                                conversationId,
                                user,
                                text,
                                clientMsgId,
                                contextItems.isEmpty()
                                        ? null
                                        : ChatMessageMeta.ofContextItems(contextItems),
                                options.model(),
                                options.mode(),
                                options.project(),
                                LocalDateTime.now()));
        events.publish(
                conversationId,
                MESSAGE_QUEUED,
                runId,
                clientMsgId,
                new QueuedMessagePayload(saved.getId(), text, saved.getCreatedAt(), contextItems));
        return saved;
    }

    /**
     * Доставляет очередь чата внутрь идущего прогона — вызывается advisor-ом между итерациями
     * tool-цикла, когда хвост истории — записанные TOOL-ответы. Ряды получают флаг {@code
     * interjection}, а возврат — их промпт-вид (с нотисом и описью вложений), готовый к дописыванию
     * в инструкции ТЕКУЩЕЙ итерации: окно итерации собрано advisor-ом памяти раньше, чем эти ряды
     * записаны, и без дописывания модель увидела бы их только следующей итерацией, которой может не
     * быть.
     *
     * @return пусто, если очередь пуста или её целиком успела забрать другая точка доставки
     */
    @Transactional
    public List<Message> flushMidTurn(String conversationId, @Nullable String runId) {
        final List<ChatMessageEntity> delivered = flush(conversationId, true, runId);
        return delivered.isEmpty()
                ? List.of()
                : chatHistory.promptMessagesFor(conversationId, delivered);
    }

    /**
     * Доставляет очередь чата обычными вопросами — терминальная обработка прогона и восстановление
     * после падения. Вызывающий обязан сперва привести хвост истории в порядок ({@code
     * repairDanglingToolCalls}): здесь запись идёт в конец как есть.
     *
     * @return {@code true}, если хоть одно сообщение доставлено — сигнал вызывающему, что у чата
     *     появился неотвеченный вопрос (и, в {@code onComplete}, повод стартовать follow-up прогон)
     */
    @Transactional
    public boolean flushPlain(String conversationId) {
        return !flush(conversationId, false, null).isEmpty();
    }

    /** Настройки прогона из первой строки очереди — до того, как {@code flushPlain} её удалит. */
    public @Nullable PendingOptions peekOptions(String conversationId) {
        final List<ChatPendingMessageEntity> rows =
                repository.findByConversationIdOrderByIdAsc(conversationId);
        return rows.isEmpty()
                ? null
                : new PendingOptions(
                        rows.getFirst().getModel(),
                        rows.getFirst().getMode(),
                        rows.getFirst().getProject());
    }

    private List<ChatMessageEntity> flush(
            String conversationId, boolean interjection, @Nullable String runId) {
        final List<ChatMessageEntity> delivered = new ArrayList<>();
        for (ChatPendingMessageEntity pending :
                repository.findByConversationIdOrderByIdAsc(conversationId)) {
            // Заявка на строку: 0 — её успела доставить другая точка (advisor против терминальной
            // обработки), и второй ряд истории она не получит.
            if (repository.claim(pending.getId()) == 0) {
                continue;
            }
            final ChatMessageEntity row =
                    chatHistory.saveDeliveredPending(
                            conversationId,
                            pending.getContent(),
                            pending.getContextItems(),
                            interjection);
            delivered.add(row);
            events.publish(
                    conversationId,
                    USER_MESSAGE,
                    runId,
                    pending.getClientMsgId(),
                    new UserMessagePayload(
                            row.getId(),
                            row.getContent(),
                            row.getCreatedAt(),
                            row.getContextItems(),
                            null,
                            null,
                            interjection ? Boolean.TRUE : null));
        }
        if (!delivered.isEmpty()) {
            log.info(
                    "[{}] Delivered {} pending message(s), interjection={}",
                    conversationId,
                    delivered.size(),
                    interjection);
        }
        return delivered;
    }
}
