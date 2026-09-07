package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Поиск по чатам для страницы поиска: чат один раз, под ним все его сообщения с совпадениями.
 * Порядок чатов — как у {@code GET /api/chats/search}: по времени последнего изменения, новые
 * первыми.
 *
 * @param total сколько сообщений с совпадениями во всех чатах вместе
 * @param chats найденные чаты
 */
public record ChatSearchGroups(int total, List<Group> chats) {

    /**
     * Один чат с его совпадениями.
     *
     * @param titleMatched запрос встретился в названии чата; тогда {@code messages} может быть
     *     пустым
     * @param messages сообщения с совпадениями в хронологическом порядке
     */
    public record Group(
            String conversationId,
            @Nullable String topic,
            LocalDateTime updatedAt,
            boolean titleMatched,
            List<Message> messages) {}

    /**
     * Одно сообщение с совпадением.
     *
     * @param role кто писал — {@code USER}, {@code ASSISTANT} или {@code TOOL}, как в истории чата
     * @param snippet фрагмент вокруг первого вхождения, той же формы, что сниппет в {@link
     *     ChatSearchResult}
     */
    public record Message(
            long id, String role, LocalDateTime createdAt, @Nullable String snippet) {}
}
