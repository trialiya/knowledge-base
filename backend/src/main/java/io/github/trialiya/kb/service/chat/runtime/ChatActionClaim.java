package io.github.trialiya.kb.service.chat.runtime;

import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Допуск к чату для действия, которое совершает <b>человек</b>, а не модель: git-команда из панели,
 * откат файловых правок, запуск сохранённого скрипта командой {@code /script}.
 *
 * <p>У всех троих правило одно — чат должен быть свой и не занят, — и живёт оно здесь одним
 * экземпляром: разойдясь, эти проверки дали бы разговор, в котором одно действие ждёт прогона, а
 * соседнее встаёт поперёк него.
 *
 * <p>Отказы отдаются {@link ResponseStatusException}, как и у {@code ChatController}: это не логика
 * чата, а допуск запроса, и коды у них общие — чужой чат обязан отвечать одинаково, из какого бы
 * эндпоинта в него ни постучали.
 */
@AllArgsConstructor
@Service
public class ChatActionClaim {

    private final ChatTopicRepository chatTopicRepository;
    private final ConversationSlots slots;

    /**
     * Пускает к чату только его владельца и только когда модель в нём не работает.
     *
     * <p>Проверка владельца — та же, что у любого эндпоинта чата, и по той же причине: команда
     * пишет ряд в историю и рассылает событие подписчикам, то есть делает с чужим разговором ровно
     * то, что делает сообщение в него. Несуществующий чат — {@code 404}, а не молчаливое заведение:
     * чат рождается вопросом или вложением, и история, начинающаяся с действий пользователяы, — это
     * опечатка в id, а не сценарий.
     *
     * <p>Действие при живом прогоне не выполняют: модель в этот момент читает и правит те же файлы
     * — pull подменит содержимое между её же двумя вызовами инструментов, а switch уведёт дерево с
     * ветки, о которой она рассуждает. Проверка на сервере, а не только серыми кнопками: модалка
     * команд может быть открыта с момента до отправки вопроса, и до её кнопок запрет фронта не
     * дотянется.
     *
     * <p>Занятость не проверяется, а <b>занимается</b>: заявка на чат — та же самая, что держит
     * прогон и сжатие ({@link ConversationSlots#claim}), поэтому «свободен» и «занял» — одно
     * атомарное действие. Проверки было бы мало: между ней и записью ряда прогон успевает
     * стартовать, и тогда {@code appendGitEvent} чинит оборванный хвост одновременно с {@code
     * ChatRunService.start} — два синтетических {@code TOOL}-ответа на один {@code tool_call_id},
     * после которых модель отвергает весь диалог. Заодно это делает запрет настоящим: пока команда
     * идёт, вопрос в этот чат получает {@code 409}, а не встаёт поперёк неё.
     *
     * <p>Заявку держат до конца команды и снимают в {@link #release} — обязательно в {@code
     * finally}: невозвращённая заявка навсегда оставила бы чат занятым.
     *
     * @return токен заявки для {@link #release}
     */
    public String claimIdleAndOwned(String conversationId) {
        final String owner =
                chatTopicRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Not found conversation id " + conversationId))
                        .getUser();
        if (!owner.equals(ChatUtils.getUser())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        try {
            return slots.claim(conversationId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw e;
            }
            // Заявка занята — в этом чате уже работают. Своё сообщение вместо общего «ответ уже
            // генерируется»: команду мог отклонить и параллельный git из другой вкладки, но для
            // пользователя это один и тот же ответ — «сейчас нельзя, попробуйте снова».
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The assistant is working on this chat right now", e);
        }
    }

    /** Снимает заявку {@link #claimIdleAndOwned}. Идемпотентно. */
    public void release(String conversationId, String claim) {
        slots.release(conversationId, claim);
    }
}
