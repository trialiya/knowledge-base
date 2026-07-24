package io.github.trialiya.kb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
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
 * Counting attachments per owner. The frontend shows these numbers as a badge on the collapsed
 * right panel, where the full list is not loaded — so the counts must be derived, and must not leak
 * between the two owner kinds (a document and a chat can never share an attachment).
 *
 * <p>Uses the H2 fixture the same way {@code SampleDataFixtureTest} does; the fixture ships one
 * chat attachment, so the document rows are added here.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-attachment-count-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
@Sql("/db/sample-data.sql")
class AttachmentCountTest {

    private static final String CHAT_ID = "c5dfa618-0ad2-4845-a976-ada46c50f9a4";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttachmentRepository attachmentRepo;

    private void insertDocumentAttachment(long id, long documentId, String fileName) {
        jdbc.update(
                """
                INSERT INTO attachments
                    (id, owner_type, document_id, conversation_id, file_name, content_type,
                     file_size, content, created_at, updated_at)
                VALUES (?, 'document', ?, NULL, ?, 'text/plain', 10, 'body',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                documentId,
                fileName);
    }

    @Test
    void countsOnlyTheGivenDocument() {
        insertDocumentAttachment(900, 76, "a.txt");
        insertDocumentAttachment(901, 76, "b.txt");
        insertDocumentAttachment(902, 75, "other.txt");

        assertThat(attachmentRepo.countByDocumentId(76L)).isEqualTo(2);
        assertThat(attachmentRepo.countByDocumentId(75L)).isEqualTo(1);
        // Документ без вложений — не ошибка, а честный ноль.
        assertThat(attachmentRepo.countByDocumentId(1L)).isZero();
    }

    @Test
    void documentAndChatCountsDoNotLeakIntoEachOther() {
        insertDocumentAttachment(903, 76, "a.txt");

        // В фикстуре у чата ровно одно вложение, и оно не должно попасть в счёт документа.
        assertThat(attachmentRepo.countByConversationId(CHAT_ID)).isEqualTo(1);
        assertThat(attachmentRepo.countByDocumentId(76L)).isEqualTo(1);
        assertThat(attachmentRepo.findByDocumentId(76L)).hasSize(1);
    }
}
