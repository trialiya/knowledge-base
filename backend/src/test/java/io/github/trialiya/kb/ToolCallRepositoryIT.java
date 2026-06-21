package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.model.tool.ToolCallEntity;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.repository.ToolCallRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Интеграционные тесты таблицы {@code tool_call} на реальном PostgreSQL: round-trip полной записи
 * вызова инструмента (полные аргументы и результат), порядок по {@code call_index}, заполнение
 * {@code created_at} из DEFAULT и инкрементальное сохранение через {@link ChatMemoryService}.
 *
 * <p>Проверяем именно то, что ломалось руками: чтение строки (нужен {@code @PersistenceCreator} с
 * {@code createdAt}) и вставка (нельзя слать {@code created_at = NULL} в NOT NULL колонку).
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
class ToolCallRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private ToolCallRepository toolCallRepo;
    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;
    @Autowired private ObjectMapper objectMapper;

    private ChatMemoryService memory() {
        return new ChatMemoryService(topicRepo, messageRepo, toolCallRepo, objectMapper);
    }

    // ── Репозиторий: round-trip и порядок ────────────────────────────────────

    @Test
    void savesAndReadsBackToolCallWithGeneratedIdAndCreatedAt() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ToolCallEntity saved =
                toolCallRepo.save(
                        new ToolCallEntity(
                                conv,
                                runId,
                                0,
                                "getFileContent",
                                "{\"path\":\"README.md\"}",
                                "OK",
                                null,
                                "full file body here",
                                "{\"lines\":42}"));

        // id выдан БД, created_at проставлен DEFAULT-ом и доступен при чтении
        assertThat(saved.getId()).isPositive();

        List<ToolCallEntity> read =
                toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runId);
        assertThat(read).hasSize(1);
        ToolCallEntity tc = read.getFirst();
        assertThat(tc.getCreatedAt()).isNotNull();
        assertThat(tc.getName()).isEqualTo("getFileContent");
        assertThat(tc.getArgumentsRaw()).isEqualTo("{\"path\":\"README.md\"}");
        assertThat(tc.getStatus()).isEqualTo("OK");
        assertThat(tc.getResultText()).isEqualTo("full file body here");
        assertThat(tc.getResultMeta()).isEqualTo("{\"lines\":42}");
        assertThat(tc.getError()).isNull();
    }

    @Test
    void ordersByCallIndexRegardlessOfInsertOrder() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        // вставляем не по порядку
        toolCallRepo.save(
                new ToolCallEntity(conv, runId, 2, "third", null, "OK", null, null, null));
        toolCallRepo.save(
                new ToolCallEntity(conv, runId, 0, "first", null, "OK", null, null, null));
        toolCallRepo.save(
                new ToolCallEntity(conv, runId, 1, "second", null, "OK", null, null, null));

        assertThat(toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runId))
                .extracting(ToolCallEntity::getName)
                .containsExactly("first", "second", "third");
    }

    @Test
    void scopesByRunId() {
        String conv = UUID.randomUUID().toString();
        String runA = UUID.randomUUID().toString();
        String runB = UUID.randomUUID().toString();

        toolCallRepo.save(new ToolCallEntity(conv, runA, 0, "a", null, "OK", null, null, null));
        toolCallRepo.save(new ToolCallEntity(conv, runB, 0, "b", null, "OK", null, null, null));

        assertThat(toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runA))
                .extracting(ToolCallEntity::getName)
                .containsExactly("a");
    }

    // ── ChatMemoryService.saveToolCallIncremental ────────────────────────────

    @Test
    void incrementalSavePreservesFullArgumentsResultAndSerializesMeta() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ToolInvocation tc =
                new ToolInvocation(
                        "updateDocument",
                        Map.of("id", 7),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("id", 7, "descriptionVersion", 3),
                        "short gist",
                        "{\"id\":7,\"body\":\"long\"}",
                        "the full, untruncated tool result text");

        memory().saveToolCallIncremental(conv, runId, 0, tc);

        List<ToolCallEntity> read =
                toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runId);
        assertThat(read).hasSize(1);
        ToolCallEntity row = read.getFirst();
        assertThat(row.getArgumentsRaw()).isEqualTo("{\"id\":7,\"body\":\"long\"}");
        assertThat(row.getResultText()).isEqualTo("the full, untruncated tool result text");
        assertThat(row.getStatus()).isEqualTo("OK");
        // resultMeta сериализован в JSON
        assertThat(row.getResultMeta()).contains("\"descriptionVersion\":3").contains("\"id\":7");
    }

    @Test
    void incrementalSaveSkipsConfiguredHousekeepingTools() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ToolInvocation skipped =
                new ToolInvocation(
                        "getCurrentDateTime",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        null,
                        "2026-06-21",
                        "{}",
                        "2026-06-21T12:00:00");

        memory().saveToolCallIncremental(conv, runId, 0, skipped);

        assertThat(toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runId))
                .isEmpty();
    }

    @Test
    void incrementalSavePersistsErrorDetails() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ToolInvocation failed =
                new ToolInvocation(
                        "searchCodebase",
                        Map.of("query", "foo"),
                        ToolInvocationStatus.ERROR,
                        "boom: index unavailable",
                        null,
                        null,
                        "{\"query\":\"foo\"}",
                        null);

        memory().saveToolCallIncremental(conv, runId, 0, failed);

        List<ToolCallEntity> read =
                toolCallRepo.findByConversationIdAndRunIdOrderByCallIndex(conv, runId);
        assertThat(read).hasSize(1);
        ToolCallEntity row = read.getFirst();
        assertThat(row.getStatus()).isEqualTo("ERROR");
        assertThat(row.getError()).isEqualTo("boom: index unavailable");
        assertThat(row.getResultText()).isNull();
    }
}
