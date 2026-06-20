package io.github.trialiya.kb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        log.error(ex.getMessage(), ex);
        ErrorResponse error =
                new ErrorResponse(HttpStatus.resolve(ex.getStatusCode().value()), ex.getMessage());
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

    private record ErrorResponse(HttpStatus status, String message) {}
}
