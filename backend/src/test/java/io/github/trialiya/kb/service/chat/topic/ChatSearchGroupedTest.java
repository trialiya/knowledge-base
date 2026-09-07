package io.github.trialiya.kb.service.chat.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.dto.ChatSearchGroups;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Поиск по всем чатам в двух формах над одними и теми же находками: дропдаун получает по чату самое
 * свежее сообщение и счётчик, страница поиска — каждое сообщение в хронологическом порядке.
 */
class ChatSearchGroupedTest {

    private static final String USER = "alice";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 12, 0);

    private ChatTopicRepository topics;
    private ChatMessageRepository messages;
    private ChatSearchService service;

    @BeforeEach
    void setUp() {
        topics = mock(ChatTopicRepository.class);
        messages = mock(ChatMessageRepository.class);
        service = new ChatSearchService(topics, messages);
    }

    private static ChatTopicEntity topic(String id, String title, LocalDateTime updatedAt) {
        return new ChatTopicEntity(id, USER, title, null, null, null, null, T0, updatedAt, false);
    }

    private static ChatMessageEntity message(
            long id, String conv, MessageType type, String content, int minutesAfterT0) {
        return new ChatMessageEntity(
                id, conv, content, type, id, false, false, T0.plusMinutes(minutesAfterT0), null);
    }

    private static ChatMessageEntity toolCrumb(long id, String conv, int minutesAfterT0) {
        return new ChatMessageEntity(
                id,
                conv,
                "жирафы (tool)",
                MessageType.ASSISTANT,
                id,
                false,
                false,
                T0.plusMinutes(minutesAfterT0),
                new ChatMessageMeta(
                        null, true, List.of(), List.of(), null, null, null, null, null, false, null,
                        List.of(), null));
    }

    @Test
    void everySearchableMessageOfAChatIsListedOldestFirst() {
        when(topics.searchByTopic(USER, "жирафы")).thenReturn(List.of());
        // От новых к старым, как отдаёт репозиторий.
        when(messages.searchForUser(eq(USER), eq("жирафы"), anyInt()))
                .thenReturn(
                        List.of(
                                message(30, "c1", MessageType.ASSISTANT, "Жирафы высокие", 30),
                                toolCrumb(20, "c1", 20),
                                message(10, "c1", MessageType.USER, "расскажи про жирафы", 10)));
        when(topics.findAllById(List.of("c1")))
                .thenReturn(List.of(topic("c1", "Про животных", T0.plusMinutes(30))));

        ChatSearchGroups groups = service.searchChatsGrouped(USER, "жирафы", 20);

        assertThat(groups.total()).isEqualTo(2);
        assertThat(groups.truncated()).isFalse();
        ChatSearchGroups.Group group = groups.chats().getFirst();
        assertThat(group.topic()).isEqualTo("Про животных");
        assertThat(group.titleMatched()).isFalse();
        assertThat(group.messages())
                .extracting(ChatSearchGroups.Message::id, ChatSearchGroups.Message::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, "USER"),
                        org.assertj.core.groups.Tuple.tuple(30L, "ASSISTANT"));
        assertThat(group.messages().getFirst().snippet()).isEqualTo("расскажи про жирафы");
    }

    /**
     * Чат, найденный только по названию, приходит без сообщений; порядок — по updatedAt, новые
     * первыми.
     */
    @Test
    void aTitleOnlyHitHasNoMessagesAndChatsAreNewestFirst() {
        when(topics.searchByTopic(USER, "жирафы"))
                .thenReturn(List.of(topic("old", "Жирафы", T0.plusMinutes(1))));
        when(messages.searchForUser(eq(USER), eq("жирафы"), anyInt()))
                .thenReturn(List.of(message(5, "new", MessageType.USER, "жирафы?", 50)));
        when(topics.findAllById(List.of("new")))
                .thenReturn(List.of(topic("new", "Вопрос", T0.plusMinutes(50))));

        ChatSearchGroups groups = service.searchChatsGrouped(USER, "жирафы", 20);

        assertThat(groups.chats())
                .extracting(
                        ChatSearchGroups.Group::conversationId,
                        ChatSearchGroups.Group::titleMatched)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("new", false),
                        org.assertj.core.groups.Tuple.tuple("old", true));
        assertThat(groups.chats().getLast().messages()).isEmpty();
        assertThat(groups.total()).isEqualTo(1);
    }

    @Test
    void theLimitCountsChatsAndTheDropdownFormAgreesOnCountsAndSnippet() {
        when(topics.searchByTopic(USER, "q")).thenReturn(List.of());
        when(messages.searchForUser(eq(USER), eq("q"), anyInt()))
                .thenReturn(
                        List.of(
                                message(3, "a", MessageType.USER, "q latest", 3),
                                message(2, "b", MessageType.USER, "q other", 2),
                                message(1, "a", MessageType.USER, "q first", 1)));
        when(topics.findAllById(List.of("a", "b")))
                .thenReturn(
                        List.of(
                                topic("a", "A", T0.plusMinutes(3)),
                                topic("b", "B", T0.plusMinutes(2))));

        List<ChatSearchResult> flat = service.searchChats(USER, "q", 1);
        ChatSearchGroups grouped = service.searchChatsGrouped(USER, "q", 1);

        assertThat(flat)
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.conversationId()).isEqualTo("a");
                            assertThat(r.messageMatchCount()).isEqualTo(2);
                            assertThat(r.snippet()).isEqualTo("q latest");
                        });
        assertThat(grouped.chats())
                .singleElement()
                .extracting(ChatSearchGroups.Group::conversationId)
                .isEqualTo("a");
        assertThat(grouped.chats().getFirst().messages())
                .extracting(ChatSearchGroups.Message::snippet)
                .containsExactly("q first", "q latest");
    }

    /**
     * Репозиторий отдаёт ровно столько строк, сколько его просили: значит, дальше есть ещё, и ответ
     * обязан сказать, что сообщений показано не все.
     */
    @Test
    void aFullScanMarksTheAnswerTruncated() {
        when(topics.searchByTopic(USER, "q")).thenReturn(List.of());
        when(messages.searchForUser(eq(USER), eq("q"), anyInt()))
                .thenAnswer(
                        inv -> {
                            int scan = inv.getArgument(2);
                            return java.util.stream.IntStream.range(0, scan)
                                    .mapToObj(
                                            i ->
                                                    message(
                                                            scan - i,
                                                            "c",
                                                            MessageType.USER,
                                                            "q",
                                                            scan - i))
                                    .toList();
                        });
        when(topics.findAllById(List.of("c"))).thenReturn(List.of(topic("c", "C", T0)));

        ChatSearchGroups groups = service.searchChatsGrouped(USER, "q", 20);

        assertThat(groups.truncated()).isTrue();
        assertThat(groups.total()).isEqualTo(groups.chats().getFirst().messages().size());
    }

    @Test
    void aBlankQueryFindsNothingWithoutTouchingTheRepositories() {
        assertThat(service.searchChatsGrouped(USER, "  ", 20))
                .isEqualTo(new ChatSearchGroups(0, false, List.of()));
    }
}
