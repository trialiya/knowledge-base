package io.github.trialiya.kb.service.chat.git;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.GitCommandPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.runtime.ChatActionClaim;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Какой след git-команда пользователя оставляет в чате, из которого её запустили.
 *
 * <p>Отдельный класс, а не пара вызовов в {@code GitCommandController}: тот про git и про
 * разрешения на репозиторий, а это правило — про чат, и меняться оно будет вместе с чатом. Панель
 * «Файлы» сюда не заходит вовсе — там нет истории, в которую можно записать.
 *
 * <p>Допуск к чату (свой ли он и не занят ли) — не здесь: это правило общее для всех действий
 * человека над чатом и живёт в {@link ChatActionClaim}.
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatGitLog {

    private final ChatHistoryService chatHistory;
    private final ChatEventService chatEvents;

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
            chatEvents.publish(
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
