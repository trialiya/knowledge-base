package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.model.ScriptResultProperties;
import io.github.trialiya.kb.model.script.StoredScriptResult;
import io.github.trialiya.kb.repository.ChatScriptResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/** {@link ChatScriptResults} over the H2 schema, starting from the fixture's one kept result. */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-chat-script-results-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
@Sql("/db/sample-data.sql")
class ChatScriptResultsTest {

    /** The fixture's first chat, which already keeps {@code r1}. */
    private static final String CHAT = "c5dfa618-0ad2-4845-a976-ada46c50f9a4";

    /** The fixture's second chat, which keeps nothing. */
    private static final String EMPTY_CHAT = "e2a7f4c1-3b8d-4f6e-9a21-5c0d7b8e9f13";

    @Autowired private ChatScriptResultRepository repository;

    private ChatScriptResults results(ScriptResultProperties properties) {
        return new ChatScriptResults(repository, properties);
    }

    @Test
    void theNextResultOfAChatTakesTheNextNumber() {
        ChatScriptResults results = results(ScriptResultProperties.defaults());

        ScriptResultStore.Kept kept = results.keep(CHAT, "count-todos", "default", "[1,2,3]");

        assertThat(kept.id()).isEqualTo("r2");
        assertThat(results.valueJson(CHAT, "r2")).contains("[1,2,3]");
        assertThat(results.list(CHAT))
                .extracting(StoredScriptResult::id, StoredScriptResult::script)
                .containsExactly(tuple("r1", null), tuple("r2", "count-todos"));
    }

    @Test
    void numbersStartAtOneInEveryChat() {
        ChatScriptResults results = results(ScriptResultProperties.defaults());

        assertThat(results.keep(EMPTY_CHAT, null, "default", "1").id()).isEqualTo("r1");
        // The other chat's r1 is a different result.
        assertThat(results.valueJson(EMPTY_CHAT, "r1")).contains("1");
        assertThat(results.valueJson(CHAT, "r1"))
                .contains("{\"file\":\"backend/build.gradle\",\"commits\":42}");
    }

    @Test
    void anIdIsReadWithOrWithoutItsPrefix() {
        ChatScriptResults results = results(ScriptResultProperties.defaults());

        assertThat(results.valueJson(CHAT, "r1")).isPresent();
        assertThat(results.valueJson(CHAT, "R1")).isPresent();
        assertThat(results.valueJson(CHAT, " 1 ")).isPresent();
        assertThat(results.valueJson(CHAT, "r9")).isEmpty();
        assertThat(results.valueJson(CHAT, "result one")).isEmpty();
        assertThat(results.valueJson(CHAT, "r")).isEmpty();
        assertThat(results.valueJson(CHAT, "r99999999999")).isEmpty();
    }

    @Test
    void aValueOverTheCeilingIsNotKeptAndTheLogIsToldWhy() {
        ChatScriptResults results = results(new ScriptResultProperties(true, 10, 50));

        ScriptResultStore.Kept kept =
                results.keep(CHAT, null, "default", "\"" + "x".repeat(20) + "\"");

        assertThat(kept.id()).isNull();
        assertThat(kept.note()).contains("max-chars=10");
        assertThat(results.list(CHAT)).hasSize(1);
    }

    @Test
    void switchedOffKeepsNothingAndSaysNothing() {
        ChatScriptResults results = results(new ScriptResultProperties(false, 1000, 50));

        ScriptResultStore.Kept kept = results.keep(CHAT, null, "default", "1");

        assertThat(kept.id()).isNull();
        assertThat(kept.note()).isNull();
    }

    @Test
    void onlyTheMostRecentResultsOfAChatStay() {
        ChatScriptResults results = results(new ScriptResultProperties(true, 1000, 2));

        results.keep(CHAT, null, "default", "2");
        results.keep(CHAT, null, "default", "3");

        assertThat(results.list(CHAT))
                .extracting(StoredScriptResult::id)
                .containsExactly("r2", "r3");
        assertThat(results.valueJson(CHAT, "r1")).isEmpty();
    }

    @Test
    void aRunOutsideAnySavedChatIsNotAnError() {
        ChatScriptResults results = results(ScriptResultProperties.defaults());

        ScriptResultStore.Kept kept = results.keep("no-such-chat", null, "default", "1");

        assertThat(kept.id()).isNull();
        assertThat(kept.note()).isNull();
    }
}
