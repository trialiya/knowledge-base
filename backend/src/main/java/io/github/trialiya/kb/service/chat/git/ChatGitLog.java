package io.github.trialiya.kb.service.chat.git;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.GitCommandPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import io.github.trialiya.kb.utils.ChatUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Что чат знает о git-командах, которые пользователь запускает из него: чей он, когда занят и какой
 * след команда в нём оставляет.
 *
 * <p>Отдельный класс, а не пара вызовов в {@code GitCommandController}: он про git и про разрешения
 * на репозиторий, а эти правила — про чат, и меняться они будут вместе с чатом. Панель «Файлы» сюда
 * не заходит вовсе — там нет ни прогона, который надо переждать, ни истории, в которую можно
 * записать.
 *
 * <p>Отказы отдаются {@link ResponseStatusException}, как и у {@code ChatController}: это не логика
 * чата, а допуск запроса, и коды у них общие — чужой чат обязан отвечать одинаково, из какого бы
 * эндпоинта в него ни постучали.
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatGitLog {

    private final ChatHistoryService chatHistory;
    private final ChatEventService chatEvents;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatRunService chatRunService;

    /**
     * Пускает к чату только его владельца и только когда модель в нём не работает.
     *
     * <p>Проверка владельца — та же, что у любого эндпоинта чата, и по той же причине: команда
     * пишет ряд в историю и рассылает событие подписчикам, то есть делает с чужим разговором ровно
     * то, что делает сообщение в него. Несуществующий чат — {@code 404}, а не молчаливое заведение:
     * чат рождается вопросом или вложением, и история, начинающаяся с git-команды, — это опечатка в
     * id, а не сценарий.
     *
     * <p>Команду при живом прогоне не выполняют: модель в этот момент читает и правит те же файлы —
     * pull подменит содержимое между её же двумя вызовами инструментов, а switch уведёт дерево с
     * ветки, о которой она рассуждает. Проверка на сервере, а не только серыми кнопками: модалка
     * команд может быть открыта с момента до отправки вопроса, и до её кнопок запрет фронта не
     * дотянется.
     *
     * <p>Занятость не проверяется, а <b>занимается</b>: заявка на чат — та же самая, что держит
     * прогон и сжатие ({@link ChatRunService#claim}), поэтому «свободен» и «занял» — одно атомарное
     * действие. Проверки было бы мало: между ней и записью ряда прогон успевает стартовать, и тогда
     * {@code appendGitEvent} чинит оборванный хвост одновременно с {@code ChatRunService.start} —
     * два синтетических {@code TOOL}-ответа на один {@code tool_call_id}, после которых модель
     * отвергает весь диалог. Заодно это делает запрет настоящим: пока команда идёт, вопрос в этот
     * чат получает {@code 409}, а не встаёт поперёк неё.
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
            return chatRunService.claim(conversationId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw e;
            }
            // Заявка занята — в этом чате уже работают. Своё сообщение вместо общего «ответ уже
            // генерируется»: команду мог отклонить и параллельный git из другой вкладки, но для
            // пользователя это один и тот же ответ — «сейчас нельзя, попробуйте снова».
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The assistant is working on this chat right now");
        }
    }

    /** Снимает заявку {@link #claimIdleAndOwned}. Идемпотентно. */
    public void release(String conversationId, String claim) {
        chatRunService.release(conversationId, claim);
    }

    /**
     * Записывает выполненную команду рядом истории и рассказывает о ней остальным вкладкам.
     *
     * <p>Пишется и отказ: «push отклонён» — то, что пользователю чаще всего нужно увидеть снова, а
     * модели — чтобы не считать ветку опубликованной. Что именно уедет модели, решает {@code
     * ChatHistoryService.promptRow} при чтении.
     *
     * <p>Ни одна неудача этой записи не превращается в отказ команды: репозиторий к этому моменту
     * уже сдвинулся, и ответить на успешный pull ошибкой значило бы заставить панель нарисовать
     * состояние, которого больше нет. Потерянный ряд — потеря, но восстановимая: ветку и изменения
     * панель перечитает сама.
     */
    public void record(
            String conversationId,
            String command,
            @Nullable String project,
            boolean ok,
            String output,
            @Nullable String branch) {
        final GitEventMeta event = new GitEventMeta(command, project, ok, output, branch);
        try {
            final ChatMessageEntity row = chatHistory.appendGitEvent(conversationId, event);
            chatEvents.publishIfPresent(
                    conversationId,
                    ChatEventType.GIT_COMMAND,
                    null,
                    null,
                    new GitCommandPayload(row.getId(), row.getCreatedAt(), event));
        } catch (RuntimeException e) {
            log.warn("Failed to record git command {} in chat {}", command, conversationId, e);
        }
    }
}
