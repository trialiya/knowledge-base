package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.chat.dto.FileRevertPayload;
import io.github.trialiya.kb.service.chat.git.ChatFileRevert;
import io.github.trialiya.kb.service.chat.git.FileRevertRefusedException;
import io.github.trialiya.kb.service.file.git.GitBusyException;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Откат файловых правок последнего ответа — то, что пользователь нажимает под блоком «изменённые
 * файлы», посмотрев, что модель написала в рабочее дерево.
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
    private final GitRegistry gitRegistry;

    public ChatFileRevertController(ChatFileRevert chatFileRevert, GitRegistry gitRegistry) {
        this.chatFileRevert = chatFileRevert;
        this.gitRegistry = gitRegistry;
    }

    /**
     * Возвращает файлы, изменённые последним ответом чата, к состоянию до него и записывает это
     * рядом истории (ряд уезжает в ответе — из него фронт рисует плашку).
     *
     * <p>Отказы отличают «не тронуто» от «не смогли»: {@code 422} — рабочее дерево осталось как
     * было и пользователю есть что прочитать (откатывать нечего, ответ правил файлы скриптом, файл
     * изменился после ответа), {@code 409} — репозиторий или чат сейчас заняты, и это повод
     * повторить.
     */
    @PostMapping("/{conversationId}/revert-files")
    public FileRevertPayload revertFiles(
            @PathVariable String conversationId,
            @RequestParam(name = "project", required = false) @Nullable String project) {
        try {
            return chatFileRevert.revertLastAnswer(conversationId, project);
        } catch (FileRevertRefusedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        } catch (GitBusyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // Проект настроен, но откатывать в нём нечем: репозиторий не открылся, дерево только
            // для чтения. Тот же расклад, что у git-команд, — и те же два кода.
            throw new ResponseStatusException(
                    gitRegistry.isAvailable(project)
                            ? HttpStatus.FORBIDDEN
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage());
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

    /** Тело отказа: сообщение, которое можно показать. */
    public record RevertError(@Nullable String message) {}
}
