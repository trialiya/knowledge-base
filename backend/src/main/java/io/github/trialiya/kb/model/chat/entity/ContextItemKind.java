package io.github.trialiya.kb.model.chat.entity;

/**
 * Вид элемента контекста, привязанного к сообщению (см. {@link ContextItem}).
 *
 * <p>Значение попадает в JSON поля {@code chat_message.meta}, поэтому имена констант — часть
 * формата хранения: переименование сломает уже записанные сообщения. Неизвестный вид при чтении
 * игнорируется, так что добавлять новые можно без миграции данных.
 */
public enum ContextItemKind {
    /** Вложение чата: {@code ref} — id строки {@code attachment}. */
    ATTACHMENT
}
