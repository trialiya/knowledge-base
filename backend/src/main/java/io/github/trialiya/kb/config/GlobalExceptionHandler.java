package io.github.trialiya.kb.config;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Клиент отключился от SSE (закрыл вкладку / переключил чат) во время записи ответа. Писать уже
     * некуда, тело отдавать нельзя (контент-тип потока — text/event-stream), поэтому просто гасим:
     * void-возврат означает «без тела». Иначе catch-all ниже сыпал бы ERROR-стек + вторичную
     * HttpMessageNotWritableException на каждый такой разрыв.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("Async request no longer usable (client disconnected): {}", ex.getMessage());
    }

    /**
     * Запрос статического ресурса, которого нет (например, браузерные автопробы вроде
     * /.well-known/appspecific/com.chrome.devtools.json от Chrome DevTools) — обычный 404, не
     * причина для ERROR-стектрейса, который иначе сыпал бы catch-all ниже.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug(ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Ошибка в самом запросе: нечисловой id в пути, отсутствующий обязательный параметр, битый JSON
     * в теле. Ответственность клиента, а не сервера, поэтому 400, а не 500 — иначе catch-all ниже
     * отдавал бы на кривую ссылку «внутреннюю ошибку» и писал в лог ERROR-стектрейс на каждый такой
     * запрос (та же причина, что и у обработчика {@link NoResourceFoundException} выше).
     */
    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        log.warn("Bad request: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        log.error(ex.getMessage(), ex);
        ErrorResponse error =
                new ErrorResponse(
                        Objects.requireNonNullElse(
                                HttpStatus.resolve(ex.getStatusCode().value()),
                                HttpStatus.INTERNAL_SERVER_ERROR),
                        ex.getMessage());
        return new ResponseEntity<>(error, ex.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex) {
        log.error(ex.getMessage(), ex);
        ErrorResponse error =
                new ErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred: " + ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private record ErrorResponse(HttpStatus status, @Nullable String message) {}
}
