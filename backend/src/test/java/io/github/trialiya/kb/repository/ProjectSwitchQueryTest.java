package io.github.trialiya.kb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Сам SQL {@link ChatMessageRepository#findProjectSwitches} — на настоящей схеме, а не на моке.
 *
 * <p>Мокнутый репозиторий отвечает тем, что ему велели, поэтому запрос, отбирающий не те ряды,
 * прошёл бы мимо {@code ChatHistoryEarlierProjectsTest} целиком. А цена ошибки тихая: список
 * репозиториев в промпте ({@code ProjectPromptService}) просто не досчитается проектов, и модель
 * перестанет знать id, которые вправе назвать.
 *
 * <p>Ряды пишутся SQL'ом, а не через {@code ChatHistoryService}: проверяется отбор по тому, что
 * лежит в таблице, включая формы, которых сервис сам не пишет, — ASSISTANT с меткой в тексте меты и
 * ряд вовсе без меты.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-project-switch-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
class ProjectSwitchQueryTest {

    private static final String CHAT = "chat-switches";
    private static final String OTHER_CHAT = "chat-other";

    private static final String SWITCHED =
            "{\"invocations\":[],\"project\":\"billing\",\"projectSwitchFrom\":\"kb\"}";

    /** Вопрос без смены проекта: незаполненных полей в мете нет вовсе. */
    private static final String NO_MARKER = "{\"invocations\":[],\"contextItems\":[]}";

    /**
     * Он же, записанный с выписанными {@code null}: такие ряды лежат в базе и подстроку содержат.
     */
    private static final String NULLS_SPELLED_OUT =
            "{\"runId\":null,\"invocations\":[],\"project\":null,\"projectSwitchFrom\":null}";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChatMessageRepository repository;

    private void insert(String conversationId, long position, String type, String meta) {
        jdbc.update(
                """
                INSERT INTO chat_message
                    (conversation_id, content, type, position, summarized, summary, created_at, meta)
                VALUES (?, 'text', ?, ?, false, false, CURRENT_TIMESTAMP, ?)
                """,
                conversationId,
                type,
                position,
                meta);
    }

    /**
     * Отбор по типу — точный: ответ ассистента маркера не носит никогда, даже когда его мета
     * содержит ту же подстроку. Отбор по мете — приблизительный: ряд, где поле выписано в {@code
     * null}, подстроку содержит, и отсеивает его уже разбор меты у вызывающего ({@code
     * ChatHistoryService#earlierProjects}).
     */
    @Test
    void theTypeFilterIsExactWhereTheMetaFilterOnlyNarrows() {
        insert(CHAT, 1, "USER", NULLS_SPELLED_OUT);
        insert(CHAT, 2, "USER", SWITCHED);
        insert(CHAT, 3, "ASSISTANT", null);
        insert(CHAT, 4, "ASSISTANT", SWITCHED);
        insert(CHAT, 5, "TOOL", SWITCHED);
        insert(CHAT, 6, "USER", null);
        insert(CHAT, 7, "USER", NO_MARKER);
        insert(OTHER_CHAT, 1, "USER", SWITCHED);

        List<ChatMessageEntity> found = repository.findProjectSwitches(CHAT);

        assertThat(found)
                .describedAs("ни чужой чат, ни ответ, ни ряд без меты, ни вопрос без маркера")
                .extracting(ChatMessageEntity::getPosition)
                .containsExactly(1L, 2L);
    }

    /**
     * Порядок — по позиции: список «выбирались раньше» читается как хронология чата, и обратный
     * порядок молча назвал бы «прежним» тот проект, на который как раз перешли.
     */
    @Test
    void switchesComeBackInTheOrderTheyHappened() {
        insert(CHAT, 5, "USER", SWITCHED);
        insert(CHAT, 2, "USER", SWITCHED);
        insert(CHAT, 9, "USER", SWITCHED);

        assertThat(repository.findProjectSwitches(CHAT))
                .extracting(ChatMessageEntity::getPosition)
                .containsExactly(2L, 5L, 9L);
    }

    @Test
    void aChatWithNothingToReadComesBackEmpty() {
        insert(CHAT, 1, "USER", NO_MARKER);
        insert(CHAT, 2, "ASSISTANT", SWITCHED);

        assertThat(repository.findProjectSwitches(CHAT)).isEmpty();
    }
}
