package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Интеграционные тесты слоя памяти чата на реальном PostgreSQL: сохранение/чтение сообщений через
 * {@link ChatMemoryService}, keyset-пагинация ({@code created_at, id}) и работа с темами чата
 * (выбранная модель, сортировка по {@code updated_at}).
 *
 * <p>Это «функционал чата» на уровне БД — без обращения к LLM. За проверку взаимодействия с моделью
 * отвечает {@code ChatModelClientIT}.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
class PostgresChatMemoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;
    @Autowired private ToolCallRepository toolCallRepo;
    @Autowired private ObjectMapper objectMapper;

    private ChatMemoryService memory() {
        return new ChatMemoryService(topicRepo, messageRepo, toolCallRepo, objectMapper);
    }

    private static String newConversation() {
        return UUID.randomUUID().toString();
    }

    private static ChatMessageEntity entity(
            String conversationId,
            String content,
            MessageType type,
            long position,
            LocalDateTime createdAt) {
        return new ChatMessageEntity(
                0L, conversationId, content, type, position, false, false, createdAt, null);
    }

    // ── ChatMemoryService: round-trip ────────────────────────────────────────

    @Test
    void savesAndReadsBackConversationMessages() {
        String conv = newConversation();
        ChatMemoryService memory = memory();

        memory.saveAll(
                conv,
                List.of(
                        new UserMessage("Что такое pgvector?"),
                        new AssistantMessage("Это расширение для векторов.")));

        List<Message> reloaded = memory.findByConversationId(conv);

        // порядок чтения задаётся ORDER BY created_at без tiebreak'а, поэтому проверяем
        // состав, а не индексы
        assertThat(reloaded).hasSize(2);
        assertThat(reloaded)
                .extracting(Message::getText)
                .containsExactlyInAnyOrder("Что такое pgvector?", "Это расширение для векторов.");
        assertThat(reloaded)
                .extracting(Message::getMessageType)
                .containsExactlyInAnyOrder(MessageType.USER, MessageType.ASSISTANT);
    }

    @Test
    void blankMessagesAreSkippedOnSave() {
        String conv = newConversation();
        ChatMemoryService memory = memory();

        memory.saveAll(
                conv, List.of(new UserMessage("   "), new AssistantMessage("реальный ответ")));

        List<Message> reloaded = memory.findByConversationId(conv);
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().getText()).isEqualTo("реальный ответ");
    }

    // ── Keyset-пагинация по (created_at, id) ─────────────────────────────────

    @Test
    void keysetPaginationWalksHistoryNewestFirst() {
        String conv = newConversation();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);
        // 5 сообщений, по одному в минуту: m0(старое) … m4(новое)
        for (int i = 0; i < 5; i++) {
            messageRepo.save(
                    new ChatMessageEntity(
                            0L,
                            conv,
                            "m" + i,
                            MessageType.USER,
                            i,
                            false,
                            false,
                            base.plusMinutes(i),
                            null));
        }

        ChatMemoryService memory = memory();

        // первая страница: 2 самых новых, в хронологическом порядке -> [m3, m4]
        ChatMemoryService.Page first = memory.findLatestPage(conv, 2);
        assertThat(first.messages())
                .extracting(ChatMessageEntity::getContent)
                .containsExactly("m3", "m4");
        assertThat(first.hasMore()).isTrue();

        // следующая страница «до» курсора (m3) -> [m1, m2]
        ChatMemoryService.Page second =
                memory.findPageBefore(
                        conv, first.oldestCursor().createdAt(), first.oldestCursor().id(), 2);
        assertThat(second.messages())
                .extracting(ChatMessageEntity::getContent)
                .containsExactly("m1", "m2");
        assertThat(second.hasMore()).isTrue();

        // последняя страница -> [m0], больше нет
        ChatMemoryService.Page third =
                memory.findPageBefore(
                        conv, second.oldestCursor().createdAt(), second.oldestCursor().id(), 2);
        assertThat(third.messages())
                .extracting(ChatMessageEntity::getContent)
                .containsExactly("m0");
        assertThat(third.hasMore()).isFalse();
    }

    // ── Темы чата: модель и сортировка ───────────────────────────────────────

    @Test
    void persistsSelectedModelOnTopic() {
        String conv = newConversation();
        topicRepo.save(new ChatTopicEntity(conv, "alice", true, "тема", null, true));

        topicRepo.updateModel(conv, "gpt-4o-mini");

        assertThat(topicRepo.findById(conv).orElseThrow().getModel()).isEqualTo("gpt-4o-mini");

        // сброс к дефолтной модели
        topicRepo.updateModel(conv, null);
        assertThat(topicRepo.findById(conv).orElseThrow().getModel()).isNull();
    }

    @Test
    void listsUserTopicsMostRecentlyUpdatedFirst() {
        String user = "bob-" + UUID.randomUUID();
        String older = newConversation();
        String newer = newConversation();
        topicRepo.save(new ChatTopicEntity(older, user, true, "старая", null, true));
        topicRepo.save(new ChatTopicEntity(newer, user, true, "новая", null, true));

        // делаем «newer» свежее по updated_at
        topicRepo.updateUpdatedAt(newer);

        List<String> ids =
                topicRepo.findAllByUserOrderByUpdatedAtDesc(user).stream()
                        .map(ChatTopicEntity::getConversationId)
                        .toList();

        assertThat(ids).first().isEqualTo(newer);
        assertThat(ids).contains(older);
    }

    // ── Поиск по сообщениям внутри чата (find-бар, Ctrl+F) ───────────────────

    @Test
    void searchMessagesFindsCaseInsensitiveSubstringChronologically() {
        String conv = newConversation();
        LocalDateTime base = LocalDateTime.of(2026, 2, 1, 10, 0);
        messageRepo.save(entity(conv, "Расскажи про PostgreSQL", MessageType.USER, 0, base));
        messageRepo.save(
                entity(conv, "Это не совпадает", MessageType.ASSISTANT, 1, base.plusMinutes(1)));
        messageRepo.save(
                entity(
                        conv,
                        "postgresql отлично подходит",
                        MessageType.ASSISTANT,
                        2,
                        base.plusMinutes(2)));

        List<MessageSearchHit> hits = memory().searchMessages(conv, "postgresql");

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).createdAt()).isBefore(hits.get(1).createdAt());
    }

    @Test
    void searchMessagesExcludesSystemAndToolCallBreadcrumbs() {
        String conv = newConversation();
        LocalDateTime base = LocalDateTime.of(2026, 2, 2, 10, 0);
        messageRepo.save(
                entity(conv, "служебное сообщение про единорога", MessageType.SYSTEM, 0, base));
        messageRepo.save(
                new ChatMessageEntity(
                        0L,
                        conv,
                        "Инструменты...\n{\"name\":\"unicornTool\"}",
                        MessageType.ASSISTANT,
                        1,
                        false,
                        false,
                        base.plusMinutes(1),
                        new ChatMessageMeta("run-1", true, List.of())));
        messageRepo.save(
                entity(
                        conv,
                        "настоящий ответ про единорога",
                        MessageType.ASSISTANT,
                        2,
                        base.plusMinutes(2)));

        List<MessageSearchHit> hits = memory().searchMessages(conv, "единорога");

        assertThat(hits).hasSize(1);
    }

    @Test
    void searchMessagesReturnsEmptyForBlankQuery() {
        String conv = newConversation();
        messageRepo.save(entity(conv, "что угодно", MessageType.USER, 0, LocalDateTime.now()));

        assertThat(memory().searchMessages(conv, "  ")).isEmpty();
    }

    // ── Поиск чатов пользователя по названию и/или сообщениям ────────────────

    @Test
    void searchChatsMatchesByTitleAndByMessageContent() {
        String user = "carol-" + UUID.randomUUID();
        String byTitle = newConversation();
        String byMessage = newConversation();
        topicRepo.save(new ChatTopicEntity(byTitle, user, true, "Обсуждение жирафов", null, true));
        topicRepo.save(new ChatTopicEntity(byMessage, user, true, "Другая тема", null, true));

        messageRepo.save(
                entity(
                        byMessage,
                        "а вот жирафы действительно высокие",
                        MessageType.USER,
                        0,
                        LocalDateTime.now()));

        List<ChatSearchResult> results = memory().searchChats(user, "жираф", 20);

        assertThat(results)
                .extracting(ChatSearchResult::conversationId)
                .containsExactlyInAnyOrder(byTitle, byMessage);

        ChatSearchResult titleResult =
                results.stream()
                        .filter(r -> r.conversationId().equals(byTitle))
                        .findFirst()
                        .orElseThrow();
        assertThat(titleResult.titleMatched()).isTrue();
        assertThat(titleResult.messageMatchCount()).isZero();

        ChatSearchResult messageResult =
                results.stream()
                        .filter(r -> r.conversationId().equals(byMessage))
                        .findFirst()
                        .orElseThrow();
        assertThat(messageResult.titleMatched()).isFalse();
        assertThat(messageResult.messageMatchCount()).isEqualTo(1);
        assertThat(messageResult.snippet()).contains("жирафы");
    }

    @Test
    void searchChatsIsScopedToUser() {
        String user = "dave-" + UUID.randomUUID();
        String otherUser = "eve-" + UUID.randomUUID();
        String mine = newConversation();
        String theirs = newConversation();
        topicRepo.save(
                new ChatTopicEntity(mine, user, true, "мой уникальный секрет123", null, true));
        topicRepo.save(
                new ChatTopicEntity(
                        theirs, otherUser, true, "чужой уникальный секрет123", null, true));

        List<ChatSearchResult> results = memory().searchChats(user, "секрет123", 20);

        assertThat(results).extracting(ChatSearchResult::conversationId).containsExactly(mine);
    }
}
