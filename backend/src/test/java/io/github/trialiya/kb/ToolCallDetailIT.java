package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatEventService;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Сборка {@link ToolCallDetail} из {@code chat_message} (взамен удалённой записи в {@code
 * tool_call}): полные аргументы — из {@code tool_data.toolCalls} ASSISTANT-сегмента, полный
 * результат — из {@code tool_data.responses} TOOL-сообщения, статус/ошибка/resultMeta — из {@code
 * meta.invocations}. Отдельно проверяются смещение callIndex во втором сегменте прогона,
 * SKIP_TOOLS-вызовы (есть в toolCalls, но не в invocations) и ERROR без TOOL-ответа.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
class ToolCallDetailIT extends AbstractPostgresIntegrationTest {

    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;

    private ChatMemoryService memory() {
        return new ChatMemoryService(
                topicRepo,
                messageRepo,
                new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))));
    }

    private long position = 0;

    private void save(
            String conv,
            MessageType type,
            @Nullable ChatMessageMeta meta,
            @Nullable ToolData toolData) {
        messageRepo.save(
                new ChatMessageEntity(
                        0,
                        conv,
                        "",
                        type,
                        ++position,
                        false,
                        false,
                        LocalDateTime.now(),
                        meta,
                        toolData));
    }

    private static ToolInvocationMeta meta(
            String name,
            ToolInvocationStatus status,
            @Nullable String error,
            @Nullable Map<String, ?> resultMeta,
            int callIndex) {
        return new ToolInvocationMeta(
                name, Map.of(), status, error, resultMeta, true, callIndex, null);
    }

    @Test
    void assemblesDetailFromSegmentAndToolResponse() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(
                                meta(
                                        "updateDocument",
                                        ToolInvocationStatus.OK,
                                        null,
                                        Map.of("id", 7),
                                        0))),
                new ToolData(
                        List.of(
                                new ToolData.Call(
                                        "call_1",
                                        "function",
                                        "updateDocument",
                                        "{\"id\":7,\"body\":\"long\"}")),
                        null));
        save(
                conv,
                MessageType.TOOL,
                null,
                new ToolData(
                        null,
                        List.of(
                                new ToolData.Response(
                                        "call_1",
                                        "updateDocument",
                                        "the full, untruncated tool result text"))));

        Optional<ToolCallDetail> detail = memory().findToolCallDetail(conv, runId, 0);

        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("updateDocument");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"id\":7,\"body\":\"long\"}");
        assertThat(detail.get().status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(detail.get().error()).isNull();
        assertThat(detail.get().resultText()).isEqualTo("the full, untruncated tool result text");
        assertThat(detail.get().resultMeta()).isEqualTo(Map.of("id", 7));
        assertThat(detail.get().createdAt()).isNotNull();
    }

    @Test
    void offsetsCallIndexIntoSecondSegmentOfSameRun() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        // Первый сегмент прогона потребил вызовы 0 и 1.
        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(
                                meta("first", ToolInvocationStatus.OK, null, null, 0),
                                meta("second", ToolInvocationStatus.OK, null, null, 1))),
                new ToolData(
                        List.of(
                                new ToolData.Call("call_0", "function", "first", "{\"n\":0}"),
                                new ToolData.Call("call_1", "function", "second", "{\"n\":1}")),
                        null));
        save(
                conv,
                MessageType.TOOL,
                null,
                new ToolData(
                        null,
                        List.of(
                                new ToolData.Response("call_0", "first", "r0"),
                                new ToolData.Response("call_1", "second", "r1"))));
        // Второй сегмент: его toolCalls[0] — это вызов прогона с callIndex=2.
        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(meta("third", ToolInvocationStatus.OK, null, null, 2))),
                new ToolData(
                        List.of(new ToolData.Call("call_2", "function", "third", "{\"n\":2}")),
                        null));
        save(
                conv,
                MessageType.TOOL,
                null,
                new ToolData(null, List.of(new ToolData.Response("call_2", "third", "r2"))));

        Optional<ToolCallDetail> detail = memory().findToolCallDetail(conv, runId, 2);

        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("third");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"n\":2}");
        assertThat(detail.get().resultText()).isEqualTo("r2");
    }

    @Test
    void mapsPositionCorrectlyWhenSkipToolPrecedesInSameSegment() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        // Вызов 0 — служебный (SKIP_TOOLS): в toolCalls он есть, в invocations — нет.
        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(meta("searchDocuments", ToolInvocationStatus.OK, null, null, 1))),
                new ToolData(
                        List.of(
                                new ToolData.Call(
                                        "call_skip", "function", "getCurrentDateTime", "{}"),
                                new ToolData.Call(
                                        "call_real",
                                        "function",
                                        "searchDocuments",
                                        "{\"q\":\"foo\"}")),
                        null));
        save(
                conv,
                MessageType.TOOL,
                null,
                new ToolData(
                        null,
                        List.of(
                                new ToolData.Response("call_skip", "getCurrentDateTime", "now"),
                                new ToolData.Response(
                                        "call_real", "searchDocuments", "search hits"))));

        Optional<ToolCallDetail> detail = memory().findToolCallDetail(conv, runId, 1);

        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("searchDocuments");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"q\":\"foo\"}");
        assertThat(detail.get().resultText()).isEqualTo("search hits");
    }

    @Test
    void errorCallWithoutToolResponseHasNullResultText() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(
                                meta(
                                        "searchCodebase",
                                        ToolInvocationStatus.ERROR,
                                        "boom: index unavailable",
                                        null,
                                        0))),
                new ToolData(
                        List.of(
                                new ToolData.Call(
                                        "call_err", "function", "searchCodebase", "{\"q\":1}")),
                        null));

        Optional<ToolCallDetail> detail = memory().findToolCallDetail(conv, runId, 0);

        assertThat(detail).isPresent();
        assertThat(detail.get().status()).isEqualTo(ToolInvocationStatus.ERROR);
        assertThat(detail.get().error()).isEqualTo("boom: index unavailable");
        assertThat(detail.get().resultText()).isNull();
    }

    @Test
    void unknownRunOrIndexYieldsEmpty() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId,
                        false,
                        List.of(meta("first", ToolInvocationStatus.OK, null, null, 0))),
                new ToolData(
                        List.of(new ToolData.Call("call_0", "function", "first", "{}")), null));

        assertThat(memory().findToolCallDetail(conv, runId, 5)).isEmpty();
        assertThat(memory().findToolCallDetail(conv, UUID.randomUUID().toString(), 0)).isEmpty();
    }
}
