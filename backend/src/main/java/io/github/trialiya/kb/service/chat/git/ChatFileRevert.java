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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
     * <p>Репозиторий не спрашивается у вызывающего, а берётся из истории самого чата ({@link
     * ChatHistoryService#lastStampedProject}): селектор проекта переключают сразу после ответа, и
     * «выбранный сейчас» — не обязательно тот, в котором ответ правил файлы. Два чекаута с
     * одинаковыми путями сделали бы такую ошибку неотличимой от успеха.
     *
     * @throws FileRevertRefusedException рабочее дерево не тронуто: откатывать нечего, ответ менял
     *     файлы неоткатываемым способом или файл изменился после ответа
     */
    public FileRevertPayload revertLastAnswer(String conversationId) {
        final String claim = chatGitLog.claimIdleAndOwned(conversationId);
        try {
            return revertClaimed(conversationId);
        } finally {
            chatGitLog.release(conversationId, claim);
        }
    }

    private FileRevertPayload revertClaimed(String conversationId) {
        final GitService git = editable(chatHistory.lastStampedProject(conversationId));
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
            plan.deletions().forEach(git::requireDeletable);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Точное совпадение не сошлось, файл больше не тот, что правили, или его вовсе нет
            // (удалён руками — тогда не сходится уже чтение). Всё это про состояние дерева, а не
            // про сбой сервиса: дерево не тронуто, и сообщение уходит пользователю как есть.
            throw new FileRevertRefusedException(
                    e.getMessage() == null ? "Cannot revert the changes" : e.getMessage());
        }

        return write(conversationId, git, plan, reverted);
    }

    /**
     * Репозиторий чата, готовый принимать правки.
     *
     * <p>Отказ отдаётся кодом прямо отсюда — как это делает {@link ChatGitLog} с чужим и занятым
     * чатом, и по той же причине: различить «проект настроен, но писать в него нельзя» ({@code
     * 403}) и «репозиторий не открылся» ({@code 503}) может только тот, кто знает, о каком проекте
     * речь, а после этого метода id проекта не знает уже никто.
     */
    private GitService editable(@Nullable String project) {
        try {
            return gitRegistry.requireEditable(project);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    gitRegistry.isAvailable(project)
                            ? HttpStatus.FORBIDDEN
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage());
        }
    }

    /**
     * Записывает пересчитанное: сначала файлы, потом ряд истории.
     *
     * <p>Дальше «всё-или-ничего» не гарантируется никем: файлы уже проверены, и упасть запись может
     * только на самом диске — а откатывать откат назад означало бы писать поверх того, что этот же
     * сбой мог оставить наполовину. Поэтому ряд пишется по факту: он перечисляет файлы, которые
     * дошли до диска, и падение с ним честнее молчания — иначе модель узнала бы о половине отката
     * ровно ничего.
     */
    private FileRevertPayload write(
            String conversationId,
            GitService git,
            FileRevertPlan plan,
            Map<String, String> reverted) {
        final List<String> done = new ArrayList<>();
        try {
            reverted.forEach(
                    (path, text) -> {
                        git.replaceTrackedFile(path, text);
                        done.add(path);
                    });
            plan.deletions()
                    .forEach(
                            (path, created) -> {
                                git.deleteFile(path, created);
                                done.add(path);
                            });
        } catch (RuntimeException e) {
            if (!done.isEmpty()) {
                record(conversationId, new FileRevertMeta(git.project().id(), List.copyOf(done)));
            }
            log.error("Revert of the last answer in chat {} failed midway", conversationId, e);
            throw new FileRevertRefusedException(
                    "The revert is incomplete: "
                            + done.size()
                            + " of "
                            + plan.paths().size()
                            + " file(s) went back before it failed — "
                            + e.getMessage());
        }
        log.info("Reverted {} file(s) of the last answer in chat {}", done.size(), conversationId);
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
