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
 * <p>Каждый чат чинится под заявкой {@link ChatRunService#claim}. Пустой реестр прогонов на старте
 * — не гарантия: сервер принимает запросы раньше, чем публикуется {@code ApplicationReadyEvent},
 * так что прогон может начаться прямо посреди этого прохода. А прогон в tool-цикле — ровно то
 * состояние, где хвост истории выглядит оборванным, хотя оборван он не был: {@code
 * repairDanglingToolCalls} дописал бы синтетический TOOL-ответ на вызов, ответ на который уже в
 * пути, и модель получила бы два ответа на один {@code callId}.
 */
@Slf4j
@Component
public class PendingMessageRecovery {

    private final ChatPendingMessageRepository repository;
    private final PendingMessageService pendingMessages;
    private final ChatHistoryService chatHistory;
    private final ChatRunService runService;

    public PendingMessageRecovery(
            ChatPendingMessageRepository repository,
            PendingMessageService pendingMessages,
            ChatHistoryService chatHistory,
            ChatRunService runService) {
        this.repository = repository;
        this.pendingMessages = pendingMessages;
        this.chatHistory = chatHistory;
        this.runService = runService;
    }

    @EventListener(ApplicationReadyEvent.class)
    void deliverLeftovers() {
        for (String conversationId : repository.conversationIds()) {
            final String claim;
            try {
                claim = runService.claim(conversationId);
            } catch (RuntimeException busy) {
                // Чат успели занять — восстанавливать нечего: и ремонт хвоста, и доставку очереди
                // начатый прогон делает сам (ChatRunService#start).
                continue;
            }
            try {
                // Прогон оборвался вместе с процессом — в хвосте истории мог остаться
                // assistant.tool_calls без TOOL-ответа. Достраиваем пару СТРОГО ДО доставки: иначе
                // записанный вопрос навсегда спрятал бы оборванную пару от ремонта, и модель
                // отвечала бы 400 на каждый следующий запрос этого чата.
                chatHistory.repairDanglingToolCalls(conversationId);
                if (pendingMessages.flushPlain(conversationId).any()) {
                    log.info("[{}] Recovered pending message(s) after restart", conversationId);
                }
            } catch (RuntimeException e) {
                // Один сорвавшийся чат не повод оставить остальные с потерянными сообщениями.
                log.warn("Failed to recover pending messages for {}", conversationId, e);
            } finally {
                runService.release(conversationId, claim);
            }
        }
    }
}
