package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.chat.dto.FileRevertPayload;
import io.github.trialiya.kb.service.chat.git.ChatFileRevert;
import io.github.trialiya.kb.service.chat.git.FileRevertRefusedException;
import io.github.trialiya.kb.service.file.git.GitBusyException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Откат файловых правок последнего ответа — то, что пользователь нажимает у строки файла в блоке
 * «изменённые файлы», посмотрев, что модель написала в рабочее дерево.
 *
 * <p>Отдельно от {@link GitCommandController}: там команды, которые пользователь даёт git'у, здесь
 * — отмена того, что сделал ассистент, и решает её не git, а история чата (см. {@code
 * ChatFileRevert}). Общего у них только то, что обе меняют рабочее дерево и обе занимают чат на
 * время работы.
 *
 * <p>Модели этот эндпоинт недоступен: инструмента, который бы его звал, нет, и в системном промпте
 * о нём ничего не сказано.
 */
@RestController
@RequestMapping("/api/chats")
public class ChatFileRevertController {

    private final ChatFileRevert chatFileRevert;

    public ChatFileRevertController(ChatFileRevert chatFileRevert) {
        this.chatFileRevert = chatFileRevert;
    }

    /**
     * Возвращает файлы, изменённые последним ответом чата, к состоянию до него и записывает это
     * рядом истории (ряд уезжает в ответе — из него фронт рисует плашку).
     *
     * <p>Тело называет файлы; без тела (или с пустым списком) откатывается всё, что ответ правил и
     * что ещё не откачено. Один ответ откатывается по файлу за раз, сколько угодно раз: каждый
     * откат — свой ряд истории.
     *
     * <p>Репозиторий не параметр: его называет история самого чата (см. {@code ChatFileRevert}) —
     * селектор проекта переключают сразу после ответа, и присланный клиентом id мог бы оказаться не
     * тем, в котором ответ правил файлы.
     *
     * <p>Отказы отличают «не тронуто» от «не смогли»: {@code 422} — рабочее дерево осталось как
     * было и пользователю есть что прочитать (откатывать нечего, файл уже откачен или ответ его не
     * трогал, ответ правил файлы скриптом, файл изменился после ответа); тем же кодом отвечает и
     * оборвавшаяся посередине запись — там сообщение говорит, сколько файлов успело вернуться.
     * {@code 409} — репозиторий или чат сейчас заняты, и это повод повторить.
     */
    @PostMapping("/{conversationId}/revert-files")
    public FileRevertPayload revertFiles(
            @PathVariable String conversationId,
            @RequestBody(required = false) @Nullable RevertRequest request) {
        try {
            return chatFileRevert.revertLastAnswer(
                    conversationId,
                    request == null || request.paths() == null ? List.of() : request.paths());
        } catch (FileRevertRefusedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), e);
        } catch (GitBusyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * Отдаёт причину отказа в теле — как {@link GitCommandController}: причина здесь и есть ответ
     * пользователю («файл изменился после ответа»), а из голого кода состояния он её не узнает.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<RevertError> refusal(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new RevertError(e.getReason()));
    }

    /** Тело запроса: какие файлы вернуть; {@code null} или пусто — все, что ещё не откачены. */
    public record RevertRequest(@Nullable List<String> paths) {}

    /** Тело отказа: сообщение, которое можно показать. */
    public record RevertError(@Nullable String message) {}
}
