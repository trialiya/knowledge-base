package io.github.trialiya.kb.model.chat.dto;

/**
 * Тип события в канале чата (см. {@link ChatEvent}). Один поток событий обслуживает и стриминг
 * ответа, и кросс-вкладочную синхронизацию.
 */
public enum ChatEventType {
    /**
     * Сообщение пользователя — эхо для остальных вкладок ({@code payload}: {@link
     * UserMessagePayload}).
     */
    USER_MESSAGE,
    /** Начало генерации ответа. */
    RUN_STARTED,
    /** Кусок ответа ассистента ({@code payload}: {@link StreamMessage}). */
    STREAM,
    /** Обновление одного вызова инструмента ({@code payload}: {@link ToolCallMessage}). */
    TOOL_CALL,
    /**
     * Итоговый список вызовов инструментов сегмента ({@code payload}: {@link ToolCallsMessage}).
     */
    TOOL_CALLS,
    /** Генерация успешно завершена. */
    RUN_DONE,
    /** Генерация остановлена пользователем. */
    RUN_STOPPED,
    /** Генерация прервана ошибкой. */
    RUN_ERROR
}
