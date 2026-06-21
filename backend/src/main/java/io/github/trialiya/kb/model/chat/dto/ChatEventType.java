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
    /**
     * Ранний сигнал: модель начала формировать вызов инструмента (генерирует аргументы), но сам
     * инструмент ещё не запущен и его имя пока недоступно. Без payload — фронт показывает «готовлю
     * данные…», если ожидание затягивается. В данный момент работает не корректно, см. <a
     * href="docs/todo/tool-preparing.md">TOOL_PREPARING</a>.
     */
    TOOL_PREPARING,
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
    RUN_ERROR,
    /** Чат удалён (в т.ч. из другой вкладки) — открытые на нём вкладки должны закрыть его. */
    CHAT_DELETED
}
