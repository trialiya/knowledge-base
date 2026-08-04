package io.github.trialiya.kb.model.chat.entity;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Элемент контекста, привязанный к сообщению пользователя: «вот это я прикладываю к вопросу».
 * Хранится в {@code chat_message.meta} (см. {@link ChatMessageMeta}), поэтому новый вид контекста
 * не требует ни миграции, ни отдельной таблицы.
 *
 * <p>Элемент — это <b>ссылка</b>, а не копия: {@code ref} резолвится при каждом чтении истории.
 * Поэтому удалённое вложение просто перестаёт попадать в промпт, а не остаётся в нём вечным
 * обещанием файла, которого больше нет.
 *
 * @param kind вид элемента; неизвестные виды при чтении отбрасываются
 * @param ref ссылка на объект в терминах {@code kind} (для {@link ContextItemKind#ATTACHMENT} — id
 *     вложения)
 * @param label подпись на момент привязки — то, что пользователь видел, когда прикладывал. Нужна
 *     фронту, чтобы нарисовать чип, не ходя за каждым объектом отдельно
 * @param payload детали, специфичные для вида (например, диапазон строк файла); пустая карта, если
 *     виду ничего не нужно
 */
public record ContextItem(
        ContextItemKind kind, String ref, @Nullable String label, Map<String, Object> payload) {

    public ContextItem {
        payload = Map.copyOf(payload);
    }

    public ContextItem(ContextItemKind kind, String ref, @Nullable String label) {
        this(kind, ref, label, Map.of());
    }
}
