package io.github.trialiya.kb.service.chat.git;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.GitCommandPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Что чат знает о git-командах, которые пользователь запускает из него: когда их нельзя запускать и
 * какой след они оставляют.
 *
 * <p>Отдельный класс, а не пара вызовов в {@code GitCommandController}: он про git и про
 * разрешения, а эти два правила — про чат, и меняться они будут вместе с чатом. Панель «Файлы»
 * через него не проходит вовсе — там нет ни прогона, который надо переждать, ни истории, в которую
 * можно записать.
 */
@AllArgsConstructor
@Service
public class ChatGitLog {

    private final ChatHistoryService chatHistory;
    private final ChatEventService chatEvents;

    /**
     * Занят ли чат прогоном модели.
     *
     * <p>Команду при живом прогоне не выполняют: модель в этот момент читает и правит те же файлы —
     * pull под ней подменит содержимое между её же двумя вызовами инструментов, а switch уведёт
     * дерево с ветки, о которой она рассуждает. Проверка на сервере, а не только серыми кнопками в
     * интерфейсе: модалка команд может быть открыта с момента до отправки вопроса, и до её кнопок
     * запрет фронта не дотянется.
     *
     * <p>Мьютекс {@code GitService} этого не закрывает: он разводит две команды между собой, а
     * прогон модели командой не является и в очередь за ним не встаёт.
     */
    public boolean busy(String conversationId) {
        return chatEvents.activeRunId(conversationId).isPresent();
    }

    /**
     * Записывает выполненную команду рядом истории и рассказывает о ней остальным вкладкам.
     *
     * <p>Пишется и отказ: «push отклонён» — то, что пользователю чаще всего нужно увидеть снова, а
     * модели — чтобы не считать ветку опубликованной. Что именно уедет модели, решает {@code
     * ChatHistoryService.promptRow} при чтении.
     */
    public void record(
            String conversationId,
            String command,
            @Nullable String project,
            boolean ok,
            String output,
            @Nullable String branch) {
        final GitEventMeta event = new GitEventMeta(command, project, ok, output, branch);
        final ChatMessageEntity row = chatHistory.appendGitEvent(conversationId, event);
        chatEvents.publish(
                conversationId,
                ChatEventType.GIT_COMMAND,
                null,
                null,
                new GitCommandPayload(row.getId(), row.getCreatedAt(), event));
    }
}
