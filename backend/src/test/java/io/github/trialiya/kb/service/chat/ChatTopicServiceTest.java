package io.github.trialiya.kb.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Заведение чата. Отдельного «создать чат» нет: строка появляется от первого дела в этом чате —
 * сообщения или вложения. Проверяется через настоящую БД, потому что смысл здесь целиком в том, что
 * строка есть: без неё внешний ключ {@code attachments.conversation_id} не пустит вложение.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-chat-topic-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
class ChatTopicServiceTest {

    @Autowired private ChatTopicRepository topicRepo;

    private ChatTopicService service;

    @BeforeEach
    void setUp() {
        service = new ChatTopicService(topicRepo);
    }

    @Test
    void firstTouchCreatesTheChat() {
        String conversationId = UUID.randomUUID().toString();

        assertThat(service.ensureExists(conversationId)).as("чата ещё не было").isFalse();

        assertThat(topicRepo.findById(conversationId)).isPresent();
    }

    /**
     * Второй вызов ничего не пересоздаёт: иначе тема и модель чата обнулялись бы на ровном месте.
     */
    @Test
    void secondTouchKeepsWhatTheChatAlreadyHas() {
        String conversationId = UUID.randomUUID().toString();
        service.ensureExists(conversationId);
        topicRepo.updateModel(conversationId, "gpt-5");

        assertThat(service.ensureExists(conversationId)).as("чат уже был").isTrue();

        assertThat(topicRepo.findById(conversationId).orElseThrow().getModel()).isEqualTo("gpt-5");
    }

    /** Иначе достаточно было бы угадать conversationId, чтобы приложить файл в чужой чат. */
    @Test
    void chatOfAnotherUserIsRefused() {
        String conversationId = UUID.randomUUID().toString();
        topicRepo.save(
                new ChatTopicEntity(
                        conversationId,
                        "somebody-else",
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        true));

        assertThatThrownBy(() -> service.ensureExists(conversationId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
