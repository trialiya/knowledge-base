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
 * Сборка {@link ToolCallDetail} из {@code chat_message} по messageId/callId/responseMessageId
 * (точечный {@code findAllById}, без скана истории чата — см. {@link
 * ChatMemoryService#findToolCallDetail}): полные аргументы — из {@code tool_data.toolCalls}
 * ASSISTANT-сегмента, полный результат — из {@code tool_data.responses} TOOL-сообщения,
 * статус/ошибка/resultMeta — из {@code meta.invocations} по тому же {@code callId}. Отдельно
 * проверяются несколько вызовов в одном сегменте, SKIP_TOOLS-вызовы (есть в toolCalls, но не в
 * invocations), ERROR без TOOL-ответа и неизвестные id.
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

    private ChatMessageEntity save(
            String conv,
            MessageType type,
            @Nullable ChatMessageMeta meta,
            @Nullable ToolData toolData) {
        return messageRepo.save(
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
            int callIndex,
            long messageId,
            String callId,
            @Nullable Long responseMessageId) {
        return new ToolInvocationMeta(
                name,
                Map.of(),
                status,
                error,
                resultMeta,
                true,
                callIndex,
                null,
                messageId,
                callId,
                responseMessageId);
    }

    @Test
    void assemblesDetailFromSegmentAndToolResponse() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "call_1",
                                                "function",
                                                "updateDocument",
                                                "{\"id\":7,\"body\":\"long\"}")),
                                null));
        ChatMessageEntity toolRow =
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
        segment =
                messageRepo.save(
                        segment.withMeta(
                                new ChatMessageMeta(
                                        runId,
                                        false,
                                        List.of(
                                                meta(
                                                        "updateDocument",
                                                        ToolInvocationStatus.OK,
                                                        null,
                                                        Map.of("id", 7),
                                                        0,
                                                        segment.getId(),
                                                        "call_1",
                                                        toolRow.getId())))));

        Optional<ToolCallDetail> detail =
                memory().findToolCallDetail(conv, segment.getId(), toolRow.getId(), "call_1");

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
    void picksCorrectCallWhenSegmentHasSeveral() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "call_0", "function", "first", "{\"n\":0}"),
                                        new ToolData.Call(
                                                "call_1", "function", "second", "{\"n\":1}")),
                                null));
        ChatMessageEntity toolRow =
                save(
                        conv,
                        MessageType.TOOL,
                        null,
                        new ToolData(
                                null,
                                List.of(
                                        new ToolData.Response("call_0", "first", "r0"),
                                        new ToolData.Response("call_1", "second", "r1"))));
        segment =
                messageRepo.save(
                        segment.withMeta(
                                new ChatMessageMeta(
                                        runId,
                                        false,
                                        List.of(
                                                meta(
                                                        "first",
                                                        ToolInvocationStatus.OK,
                                                        null,
                                                        null,
                                                        0,
                                                        segment.getId(),
                                                        "call_0",
                                                        toolRow.getId()),
                                                meta(
                                                        "second",
                                                        ToolInvocationStatus.OK,
                                                        null,
                                                        null,
                                                        1,
                                                        segment.getId(),
                                                        "call_1",
                                                        toolRow.getId())))));

        Optional<ToolCallDetail> detail =
                memory().findToolCallDetail(conv, segment.getId(), toolRow.getId(), "call_1");

        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("second");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"n\":1}");
        assertThat(detail.get().resultText()).isEqualTo("r1");
    }

    @Test
    void resolvesRealCallEvenWithSkipToolInSameSegment() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        // getCurrentDateTime — служебный (SKIP_TOOLS): в toolCalls он есть, в invocations — нет.
        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "call_skip",
                                                "function",
                                                "getCurrentDateTime",
                                                "{}"),
                                        new ToolData.Call(
                                                "call_real",
                                                "function",
                                                "searchDocuments",
                                                "{\"q\":\"foo\"}")),
                                null));
        ChatMessageEntity toolRow =
                save(
                        conv,
                        MessageType.TOOL,
                        null,
                        new ToolData(
                                null,
                                List.of(
                                        new ToolData.Response(
                                                "call_skip", "getCurrentDateTime", "now"),
                                        new ToolData.Response(
                                                "call_real", "searchDocuments", "search hits"))));
        segment =
                messageRepo.save(
                        segment.withMeta(
                                new ChatMessageMeta(
                                        runId,
                                        false,
                                        List.of(
                                                meta(
                                                        "searchDocuments",
                                                        ToolInvocationStatus.OK,
                                                        null,
                                                        null,
                                                        1,
                                                        segment.getId(),
                                                        "call_real",
                                                        toolRow.getId())))));

        Optional<ToolCallDetail> detail =
                memory().findToolCallDetail(conv, segment.getId(), toolRow.getId(), "call_real");

        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("searchDocuments");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"q\":\"foo\"}");
        assertThat(detail.get().resultText()).isEqualTo("search hits");
    }

    @Test
    void errorCallWithoutToolResponseHasNullResultText() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "call_err",
                                                "function",
                                                "searchCodebase",
                                                "{\"q\":1}")),
                                null));
        segment =
                messageRepo.save(
                        segment.withMeta(
                                new ChatMessageMeta(
                                        runId,
                                        false,
                                        List.of(
                                                meta(
                                                        "searchCodebase",
                                                        ToolInvocationStatus.ERROR,
                                                        "boom: index unavailable",
                                                        null,
                                                        0,
                                                        segment.getId(),
                                                        "call_err",
                                                        null)))));

        Optional<ToolCallDetail> detail =
                memory().findToolCallDetail(conv, segment.getId(), null, "call_err");

        assertThat(detail).isPresent();
        assertThat(detail.get().status()).isEqualTo(ToolInvocationStatus.ERROR);
        assertThat(detail.get().error()).isEqualTo("boom: index unavailable");
        assertThat(detail.get().resultText()).isNull();
    }

    @Test
    void unknownIdsYieldEmpty() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();

        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(new ToolData.Call("call_0", "function", "first", "{}")),
                                null));
        segment =
                messageRepo.save(
                        segment.withMeta(
                                new ChatMessageMeta(
                                        runId,
                                        false,
                                        List.of(
                                                meta(
                                                        "first",
                                                        ToolInvocationStatus.OK,
                                                        null,
                                                        null,
                                                        0,
                                                        segment.getId(),
                                                        "call_0",
                                                        null)))));

        assertThat(memory().findToolCallDetail(conv, segment.getId(), null, "call_missing"))
                .isEmpty();
        assertThat(memory().findToolCallDetail(conv, 9_999_999L, null, "call_0")).isEmpty();
        assertThat(
                        memory().findToolCallDetail(
                                        UUID.randomUUID().toString(),
                                        segment.getId(),
                                        null,
                                        "call_0"))
                .isEmpty();
    }
}
