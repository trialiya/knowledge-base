package io.github.trialiya.kb.service.chat.git;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.FileRevertPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.git.dto.TextEdit;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Откат файловых правок последнего ответа: пользователь посмотрел, что модель написала в рабочее
 * дерево, и вернул файлы к состоянию до ответа.
 *
 * <p>Что откатывать, сервис вычитывает из истории сам — клиент называет только чат и репозиторий.
 * Правки {@code editFile} обратимы своими же аргументами (см. {@link FileRevertPlan}), созданные
 * файлы удаляются, поэтому ничего дополнительного при записи файлов хранить не приходится.
 *
 * <p>Откатывается ровно последний ответ. Не потому, что раньше нельзя технически, а потому, что
 * поверх старого блока обычно уже лежат другие правки, и «вернуть как было» перестаёт быть
 * однозначным действием; для всего остального есть git.
 *
 * <p>Сначала считаем, потом пишем: новое содержимое каждого файла собирается в памяти ({@code
 * GitService.previewEdited}), и только когда сошлись все файлы — они записываются. Иначе ответ,
 * тронувший три файла, мог бы откатиться наполовину, а половина отката хуже отказа.
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatFileRevert {

    private final ChatGitLog chatGitLog;
    private final ChatHistoryService chatHistory;
    private final ChatEventService chatEvents;
    private final GitRegistry gitRegistry;

    /**
     * Откатывает файловые правки последнего ответа чата и записывает это рядом истории.
     *
     * <p>Заявка на чат берётся тем же {@link ChatGitLog#claimIdleAndOwned}, что и у git-команд: чат
     * чужим не откатывают, а во время прогона — не откатывают вовсе, иначе модель правит те же
     * файлы, из-под которых их уводят.
     *
     * @param project репозиторий, в котором показан блок изменений; {@code null} — проект по
     *     умолчанию
     * @throws FileRevertRefusedException рабочее дерево не тронуто: откатывать нечего, ответ менял
     *     файлы неоткатываемым способом или файл изменился после ответа
     */
    public FileRevertPayload revertLastAnswer(String conversationId, @Nullable String project) {
        final String claim = chatGitLog.claimIdleAndOwned(conversationId);
        try {
            return revertClaimed(conversationId, project);
        } finally {
            chatGitLog.release(conversationId, claim);
        }
    }

    private FileRevertPayload revertClaimed(String conversationId, @Nullable String project) {
        final GitService git = gitRegistry.requireEditable(project);
        final List<ChatMessageEntity> answer = chatHistory.lastAnswerRows(conversationId);
        // Ряд отката остаётся в хвосте ответа (ходом он не является), поэтому повторный откат
        // виден прямо здесь — и это не педантизм: второй раз план собрался бы тот же, а файлы уже
        // не совпадают, и пользователь получил бы «файл изменился» вместо «уже откачено».
        if (answer.stream()
                .anyMatch(row -> row.getMeta() != null && row.getMeta().fileRevert() != null)) {
            throw new FileRevertRefusedException(
                    "The file changes from this answer have already been reverted.");
        }
        final FileRevertPlan plan = FileRevertPlan.of(answer);
        if (plan.isEmpty()) {
            throw new FileRevertRefusedException("This answer changed no files.");
        }

        final Map<String, String> reverted = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<TextEdit>> file : plan.edits().entrySet()) {
                reverted.put(file.getKey(), git.previewEdited(file.getKey(), file.getValue()));
            }
            for (String path : plan.deletions()) {
                git.requireDeletable(path);
            }
        } catch (IllegalArgumentException e) {
            // Точное совпадение не сошлось (или файл больше не тот, что правили) — это и есть
            // проверка целостности отката, а не сбой: за неё сообщение и уходит пользователю.
            throw new FileRevertRefusedException(
                    e.getMessage() == null ? "Cannot revert" : e.getMessage());
        }

        reverted.forEach(git::replaceTrackedFile);
        plan.deletions().forEach(git::deleteFile);
        log.info(
                "Reverted {} file(s) of the last answer in chat {}",
                plan.paths().size(),
                conversationId);

        return record(conversationId, new FileRevertMeta(git.project().id(), plan.paths()));
    }

    /**
     * Записывает откат рядом истории и рассказывает о нём остальным вкладкам.
     *
     * <p>В отличие от {@code ChatGitLog.record}, неудача этой записи не проглатывается: там ряд
     * лишь пересказывает то, что панель перечитает сама, а здесь без ряда об откате не узнает
     * модель — и следующим же ходом она будет рассуждать о правках, которых на диске нет.
     */
    private FileRevertPayload record(String conversationId, FileRevertMeta revert) {
        final ChatMessageEntity row = chatHistory.appendFileRevert(conversationId, revert);
        final FileRevertPayload payload =
                new FileRevertPayload(row.getId(), row.getCreatedAt(), revert);
        chatEvents.publish(conversationId, ChatEventType.FILE_REVERT, null, null, payload);
        return payload;
    }
}
