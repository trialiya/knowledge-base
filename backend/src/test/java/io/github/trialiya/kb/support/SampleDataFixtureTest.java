package io.github.trialiya.kb.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
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
import org.springframework.test.context.jdbc.Sql;

/**
 * Loads {@code db/sample-data.sql} against the H2 schema and sanity-checks it — both a regression
 * test for the fixture itself (catches SQL that no longer matches {@code db/migration-h2}) and a
 * worked example of using the fixture in a test, per CLAUDE.md ("Тестовые данные для H2").
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-sample-data-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
@Sql("/db/sample-data.sql")
class SampleDataFixtureTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentRepository documentRepo;
    @Autowired private ChatMessageRepository chatMessageRepo;

    @Test
    void loadsAllFixtureTables() {
        assertThat(jdbc.queryForObject("select count(*) from chat_topic", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from chat_message", Integer.class))
                .isEqualTo(16);
        assertThat(jdbc.queryForObject("select count(*) from tool_call", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from documents", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from document_history", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from attachments", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from embedding_tasks", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void chatMessagesRoundTripToolDataAndMeta() {
        List<ChatMessageEntity> messages =
                chatMessageRepo
                        .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                                "c5dfa618-0ad2-4845-a976-ada46c50f9a4");

        assertThat(messages).hasSize(16);
        // ASSISTANT breadcrumb message carries a parsed meta with tool invocations
        ChatMessageEntity toolBreadcrumb =
                messages.stream().filter(m -> m.getId() == 1639).findFirst().orElseThrow();
        assertThat(toolBreadcrumb.getMeta()).isNotNull();
        assertThat(toolBreadcrumb.getInvocations()).hasSize(1);
        assertThat(toolBreadcrumb.getToolData()).isNotNull();
        assertThat(toolBreadcrumb.getToolData().toolCalls()).hasSize(2);
    }

    @Test
    void newFixtureDocumentLinksToExistingDocumentAndFile() {
        DocumentEntity doc = documentRepo.findById(77L).orElseThrow();
        assertThat(doc.getParentId()).isEqualTo(75L);
        assertThat(doc.getDescription())
                .contains("/files?path=backend/build.gradle")
                .contains("/?doc=76")
                .contains("/?doc=76#");
    }

    @Test
    void chatAndDocumentAttachmentsAreQueryable() {
        String chatFile =
                jdbc.queryForObject(
                        "select file_name from attachments where owner_type = 'chat'",
                        String.class);
        String docFile =
                jdbc.queryForObject(
                        "select file_name from attachments where owner_type = 'document'",
                        String.class);
        assertThat(chatFile).isEqualTo("gradle-build-error.log");
        assertThat(docFile).isEqualTo("build.gradle");
    }
}
