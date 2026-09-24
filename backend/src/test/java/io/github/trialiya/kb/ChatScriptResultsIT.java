package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.config.model.ScriptResultProperties;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatScriptResultRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.script.ChatScriptResults;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ChatScriptResults} on real PostgreSQL, outside a transaction — the way a script run calls
 * it. What H2 cannot show: that a refused insert (the foreign key of a chat that has no row) is
 * swallowed without poisoning anything that follows, since on Postgres a failed statement aborts
 * the transaction it ran in.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatScriptResultsIT extends AbstractPostgresIntegrationTest {

    @Autowired private ChatScriptResultRepository repository;
    @Autowired private ChatTopicRepository topics;

    private final String chat = "script-results-" + UUID.randomUUID();
    private ChatScriptResults results;

    @BeforeEach
    void setUp() {
        topics.save(new ChatTopicEntity(chat, "alice", "тема", null, null, true));
        results = new ChatScriptResults(repository, new ScriptResultProperties(true, 1000, 2));
    }

    @AfterEach
    void tearDown() {
        topics.deleteById(chat);
    }

    @Test
    void numbersReadsAndDropsTheOldest() {
        assertThat(results.keep(chat, null, "kb", "1").id()).isEqualTo("r1");
        assertThat(results.keep(chat, "count", "kb", "{\"n\":2}").id()).isEqualTo("r2");
        assertThat(results.keep(chat, null, "kb", "3").id()).isEqualTo("r3");

        assertThat(results.valueJson(chat, "r2")).contains("{\"n\":2}");
        assertThat(results.valueJson(chat, "r1")).isEmpty();
        assertThat(results.list(chat)).extracting(r -> r.id()).containsExactly("r2", "r3");
    }

    @Test
    void aChatWithNoRowIsRefusedAndNothingAfterItIsHurt() {
        assertThat(results.keep("no-such-chat-" + UUID.randomUUID(), null, "kb", "1").id())
                .isNull();

        assertThat(results.keep(chat, null, "kb", "1").id()).isEqualTo("r1");
    }

    @Test
    void deletingTheChatTakesItsResults() {
        results.keep(chat, null, "kb", "1");

        topics.deleteById(chat);

        assertThat(repository.maxSeq(chat)).isZero();
    }
}
