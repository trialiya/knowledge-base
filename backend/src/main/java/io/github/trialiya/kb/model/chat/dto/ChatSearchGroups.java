package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Поиск по чатам для страницы поиска: чат один раз, под ним все его сообщения с совпадениями.
 * Порядок чатов — как у {@code GET /api/chats/search}: по времени последнего изменения, новые
 * первыми.
 *
 * <p>Сообщения берутся из просмотра самых свежих совпадений по всем чатам пользователя — того же
 * ограниченного просмотра, по которому {@code /api/chats/search} считает {@code messageMatchCount}.
 * Когда просмотр упёрся в свой предел, у старых чатов (или у чата с очень многими совпадениями)
 * здесь не все сообщения — об этом говорит {@code truncated}.
 *
 * @param total сколько строк с совпадениями в чатах, вошедших в ответ
 * @param truncated просмотр совпадений упёрся в предел: у каких-то чатов сообщения показаны не все,
 *     а какие-то старые чаты могли не попасть вовсе
 * @param chats найденные чаты
 */
public record ChatSearchGroups(int total, boolean truncated, List<Group> chats) {

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
     * Одно сообщение с совпадениями.
     *
     * @param role кто писал — {@code USER}, {@code ASSISTANT} или {@code TOOL}, как в истории чата
     * @param fragments вхождения запроса по порядку в тексте сообщения — по фрагменту на строку, в
     *     форме сниппета из {@link ChatSearchResult}; сообщение, совпавшее только через перенос
     *     строки, несёт один фрагмент от своего начала
     */
    public record Message(long id, String role, LocalDateTime createdAt, List<String> fragments) {}
}
