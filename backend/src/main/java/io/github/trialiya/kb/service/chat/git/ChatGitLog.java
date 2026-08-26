package io.github.trialiya.kb.service.chat.git;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.GitCommandPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
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
     * <p>Занятость определяется по активному прогону в канале событий и потому знает не всё: между
     * заявкой прогона и его стартом есть окно, в котором чат уже занят, а канал об этом ещё не
     * знает. Это защита в глубину, а не замок: мьютекс самого репозитория остаётся тем, что не даёт
     * двум командам разойтись, а окно закрывать нечем — прогон и команда идут разными путями.
     */
    public void requireIdleAndOwned(String conversationId) {
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
        if (chatEvents.activeRunId(conversationId).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The assistant is working on this chat right now");
        }
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
