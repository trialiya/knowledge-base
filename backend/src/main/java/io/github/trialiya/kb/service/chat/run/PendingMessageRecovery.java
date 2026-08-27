package io.github.trialiya.kb.service.chat.run;

import io.github.trialiya.kb.repository.ChatPendingMessageRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Доставляет в историю очереди, оставшиеся от прогонов, которых больше нет: процесс упал (или его
 * остановили жёстко) между приёмом сообщения и его доставкой. Без этого такое сообщение осталось бы
 * в {@code chat_pending_message} до следующего вопроса в тот же чат — то есть, с точки зрения
 * пользователя, потерялось бы, хотя ответ «принято» он уже получил.
 *
 * <p>Отвечать на доставленное здесь никто не начинает: перезапуск сервиса — не повод разослать
 * столько вызовов модели, сколько чатов не успели опустеть. Сообщение становится последним вопросом
 * истории, а это ровно то состояние, где чат предлагает «Повторить» (см. {@link
 * ChatHistoryService#unansweredUserMessage}).
 *
 * <p>Прогонов на старте нет по построению — реестр {@code ChatRunService} живёт в памяти, — поэтому
 * гонки с advisor-ом здесь быть не может; claim-through-delete в {@link PendingMessageService} всё
 * равно её закрывает.
 */
@Slf4j
@Component
public class PendingMessageRecovery {

    private final ChatPendingMessageRepository repository;
    private final PendingMessageService pendingMessages;
    private final ChatHistoryService chatHistory;

    public PendingMessageRecovery(
            ChatPendingMessageRepository repository,
            PendingMessageService pendingMessages,
            ChatHistoryService chatHistory) {
        this.repository = repository;
        this.pendingMessages = pendingMessages;
        this.chatHistory = chatHistory;
    }

    @EventListener(ApplicationReadyEvent.class)
    void deliverLeftovers() {
        for (String conversationId : repository.conversationIds()) {
            try {
                // Прогон оборвался вместе с процессом — в хвосте истории мог остаться
                // assistant.tool_calls без TOOL-ответа. Достраиваем пару СТРОГО ДО доставки: иначе
                // записанный вопрос навсегда спрятал бы оборванную пару от ремонта, и модель
                // отвечала бы 400 на каждый следующий запрос этого чата.
                chatHistory.repairDanglingToolCalls(conversationId);
                if (pendingMessages.flushPlain(conversationId)) {
                    log.info("[{}] Recovered pending message(s) after restart", conversationId);
                }
            } catch (RuntimeException e) {
                // Один сорвавшийся чат не повод оставить остальные с потерянными сообщениями.
                log.warn("Failed to recover pending messages for {}", conversationId, e);
            }
        }
    }
}
