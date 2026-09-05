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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Откат файловых правок последнего ответа: пользователь посмотрел, что модель написала в рабочее
 * дерево, и вернул файлы к состоянию до ответа — по одному или все разом.
 *
 * <p>Что откатывать, сервис вычитывает из истории сам — клиент называет чат и, если решает
 * пофайлово, пути. Правки {@code editFile} обратимы своими же аргументами (см. {@link
 * FileRevertPlan}), созданные файлы удаляются, поэтому ничего дополнительного при записи файлов
 * хранить не приходится.
 *
 * <p>Откатывается ровно последний ответ. Не потому, что раньше нельзя технически, а потому, что
 * поверх старого блока обычно уже лежат другие правки, и «вернуть как было» перестаёт быть
 * однозначным действием; для всего остального есть git.
 *
 * <p>Сначала считаем, потом пишем: новое содержимое каждого файла собирается в памяти ({@code
 * GitService.previewEdited}), и только когда сошлись все файлы — они записываются. Иначе запрос,
 * тронувший три файла, мог бы откатиться наполовину, а половина отката хуже отказа.
 *
 * <p>Каждый откат оставляет свой ряд, и следующий вычитает из плана то, что прежние ряды уже
 * вернули: так файлы одного ответа откатываются по очереди, а повторный откат того же файла
 * получает «уже откачено», а не «файл изменился» — план для него собрался бы тот же, но файл на
 * диске уже не тот.
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
     * <p>{@code paths} — какие файлы вернуть; пустой список — все, что ответ правил и что ещё не
     * откачено.
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
     *     файлы неоткатываемым способом, названный файл ответ не трогал или уже откачен, файл
     *     изменился после ответа
     */
    public FileRevertPayload revertLastAnswer(String conversationId, List<String> paths) {
        final String claim = chatGitLog.claimIdleAndOwned(conversationId);
        try {
            return revertClaimed(conversationId, paths);
        } finally {
            chatGitLog.release(conversationId, claim);
        }
    }

    private FileRevertPayload revertClaimed(String conversationId, List<String> paths) {
        final GitService git = editable(chatHistory.lastStampedProject(conversationId));
        final List<ChatMessageEntity> answer = chatHistory.lastAnswerRows(conversationId);
        final FileRevertPlan whole = FileRevertPlan.of(answer);
        if (whole.isEmpty()) {
            throw new FileRevertRefusedException("This answer changed no files.");
        }
        final FileRevertPlan plan = remaining(whole, revertedPaths(answer), paths);

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
                    e.getMessage() == null ? "Cannot revert the changes" : e.getMessage(), e);
        }

        return write(conversationId, git, plan, reverted);
    }

    /**
     * Что откатывать этим запросом: названные файлы, а без имён — всё, что ещё не откачено.
     *
     * <p>Отказ называет файл и причину: человек нажал кнопку у конкретной строки, и «откатывать
     * нечего» ему не объяснит, что не так именно с ней.
     */
    private static FileRevertPlan remaining(
            FileRevertPlan whole, Set<String> reverted, List<String> paths) {
        if (paths.isEmpty()) {
            final Set<String> left = new LinkedHashSet<>(whole.paths());
            left.removeAll(reverted);
            if (left.isEmpty()) {
                throw new FileRevertRefusedException(
                        "The file changes from this answer have already been reverted.");
            }
            return whole.only(left);
        }
        final Set<String> wanted = new LinkedHashSet<>(paths);
        for (String path : wanted) {
            if (reverted.contains(path)) {
                throw new FileRevertRefusedException(
                        "The changes to " + path + " have already been reverted.");
            }
            if (!whole.paths().contains(path)) {
                throw new FileRevertRefusedException("This answer did not change " + path + ".");
            }
        }
        return whole.only(wanted);
    }

    /**
     * Файлы, которые прежние откаты этого же ответа уже вернули. Ряды отката остаются в хвосте
     * ответа (ходом они не являются), поэтому читаются из тех же рядов, что и план.
     */
    private static Set<String> revertedPaths(List<ChatMessageEntity> answer) {
        final Set<String> reverted = new LinkedHashSet<>();
        for (ChatMessageEntity row : answer) {
            if (row.getMeta() != null && row.getMeta().fileRevert() != null) {
                reverted.addAll(row.getMeta().fileRevert().paths());
            }
        }
        return reverted;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(
                    gitRegistry.isAvailable(project)
                            ? HttpStatus.FORBIDDEN
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage(),
                    e);
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
            log.error(
                    "Revert of files of the last answer in chat {} failed midway",
                    conversationId,
                    e);
            throw new FileRevertRefusedException(
                    "The revert is incomplete: "
                            + done.size()
                            + " of "
                            + plan.paths().size()
                            + " file(s) went back before it failed — "
                            + e.getMessage(),
                    e);
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
