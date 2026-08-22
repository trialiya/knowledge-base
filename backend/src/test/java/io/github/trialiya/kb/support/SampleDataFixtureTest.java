package io.github.trialiya.kb.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.util.List;
import java.util.Objects;
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
 * worked example of using the fixture in a test, per {@code .claude/rules/backend-data.md}.
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

    /**
     * The chat names the project its tools ran in. Asserted because the column is nullable and the
     * fixture would keep loading without it — the fixture is also manual-QA data, and a chat with
     * no project shows the selector's fallback rather than a real selection.
     */
    @Test
    void theFixtureChatNamesItsProject() {
        assertThat(
                        jdbc.queryForObject(
                                "select project from chat_topic where conversation_id = ?",
                                String.class,
                                "c5dfa618-0ad2-4845-a976-ada46c50f9a4"))
                .isEqualTo("default");
    }

    @Test
    void loadsAllFixtureTables() {
        assertThat(jdbc.queryForObject("select count(*) from chat_topic", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from chat_message", Integer.class))
                .isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from tool_call_index", Integer.class))
                .isEqualTo(10);
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

        assertThat(messages).hasSize(20);
        // ASSISTANT breadcrumb message carries a parsed meta with tool invocations
        ChatMessageEntity toolBreadcrumb =
                messages.stream().filter(m -> m.getId() == 1639).findFirst().orElseThrow();
        assertThat(toolBreadcrumb.getMeta()).isNotNull();
        assertThat(toolBreadcrumb.getInvocations()).hasSize(1);
        assertThat(toolBreadcrumb.getToolData()).isNotNull();
        assertThat(toolBreadcrumb.getToolData().toolCalls()).hasSize(2);

        // Модель ответа: у прогонов, записанных после появления поля, она есть, у более
        // раннего — нет. Обе половины фикстуры нужны: null здесь значит «неизвестно», и
        // подпись под таким ответом не рисуется вовсе.
        assertThat(
                        messages.stream()
                                .filter(m -> m.getMeta() != null && m.getMeta().model() != null)
                                .map(m -> m.getMeta().model()))
                .containsOnly("deepseek-chat");
        assertThat(messages.stream().filter(m -> m.getId() == 1657).findFirst().orElseThrow())
                .satisfies(m -> assertThat(m.getMeta().model()).isEqualTo("deepseek-chat"));
        assertThat(toolBreadcrumb.getMeta().model()).isNull();

        // Вопрос с приложенным вложением: в сообщении лежит ссылка, а не содержимое файла.
        ChatMessageEntity question =
                messages.stream().filter(m -> m.getId() == 1638).findFirst().orElseThrow();
        assertThat(question.getContextItems())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.kind()).isEqualTo(ContextItemKind.ATTACHMENT);
                            assertThat(item.ref()).isEqualTo("1");
                            assertThat(item.label()).isEqualTo("gradle-build-error.log");
                        });
    }

    /**
     * Every plaque in the fixture chat must be openable. The frontend needs {@code callId} on the
     * invocation itself ({@code canShowDetail}) and a {@code tool_call_index} row behind it —
     * without either the modal cannot be reached at all. Asserted here rather than left to manual
     * QA: a row added without a callId looks fine in the list and only fails on click.
     */
    @Test
    void everyInvocationResolvesThroughTheToolCallIndex() {
        List<String> indexed =
                jdbc.queryForList(
                        "select call_id from tool_call_index where conversation_id = ?",
                        String.class,
                        "c5dfa618-0ad2-4845-a976-ada46c50f9a4");

        assertThat(
                        chatMessageRepo
                                .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                                        "c5dfa618-0ad2-4845-a976-ada46c50f9a4")
                                .stream()
                                .map(ChatMessageEntity::getInvocations)
                                .filter(Objects::nonNull)
                                .flatMap(List::stream))
                .isNotEmpty()
                .allSatisfy(
                        inv -> {
                            assertThat(inv.callId()).isNotBlank();
                            assertThat(indexed).contains(inv.callId());
                        });
    }

    /**
     * The one call in the fixture whose result is text rather than a list or a tree — it is what
     * makes the tool-call modal's "Обзор" mode reachable in manual QA (see the file header).
     */
    @Test
    void fileContentCallCarriesTextResult() {
        String responseData =
                chatMessageRepo
                        .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                                "c5dfa618-0ad2-4845-a976-ada46c50f9a4")
                        .stream()
                        .filter(m -> m.getId() == 1656)
                        .findFirst()
                        .orElseThrow()
                        .getToolData()
                        .responses()
                        .stream()
                        .filter(r -> "getFileContent".equals(r.name()))
                        .findFirst()
                        .orElseThrow()
                        .responseData();

        assertThat(responseData).contains("\"language\":\"groovy\"").contains("plugins {");
    }

    @Test
    void newFixtureDocumentLinksToExistingDocumentAndFile() {
        DocumentEntity doc = documentRepo.findById(77L).orElseThrow();
        assertThat(doc.getParentId()).isEqualTo(75L);
        // created_at is populated by the fixture, not left to the column default — the
        // knowledge-base "Info" panel renders it.
        assertThat(doc.getCreatedAt()).isNotNull().isEqualTo(doc.getUpdatedAt());
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
