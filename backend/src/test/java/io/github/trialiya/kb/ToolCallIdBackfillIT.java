package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
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
 * {@link ChatMemoryService#backfillToolCallIds()} на данных в старом формате (meta.invocations без
 * messageId/callId/responseMessageId, ровно как их писала версия до этого PR) — проверяет, что
 * бэкафилл восстанавливает id так же, как их сейчас пишет {@link ChatMemoryService#attachRunMeta},
 * что он идемпотентен, и что чаты без {@code tool_data} (дотуловдатовая эпоха) не трогает.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommonConfig.class, JdbcConfig.class, PgVectorJdbcConfig.class})
class ToolCallIdBackfillIT extends AbstractPostgresIntegrationTest {

    @Autowired private ChatTopicRepository topicRepo;
    @Autowired private ChatMessageRepository messageRepo;

    private ChatMemoryService memory() {
        return new ChatMemoryService(
                topicRepo,
                messageRepo,
                new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))));
    }

    private long position = 0;

    private void newConversation(String conv) {
        topicRepo.save(
                new ChatTopicEntity(
                        conv,
                        "admin",
                        false,
                        "t",
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        true));
    }

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

    /** Мета в старом формате — как её писала версия attachRunMeta до этого PR. */
    private static ToolInvocationMeta oldMeta(
            String name, ToolInvocationStatus status, int callIndex) {
        return new ToolInvocationMeta(
                name,
                java.util.Map.of(),
                status,
                null,
                null,
                true,
                callIndex,
                null,
                null,
                null,
                null);
    }

    @Test
    void fillsIdsFromToolDataAndMatchesFindToolCallDetail() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        newConversation(conv);

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
                                                "call_1", "updateDocument", "the full result"))));
        messageRepo.save(
                segment.withMeta(
                        new ChatMessageMeta(
                                runId,
                                false,
                                List.of(oldMeta("updateDocument", ToolInvocationStatus.OK, 0)))));

        ChatMemoryService.BackfillResult result = memory().backfillToolCallIds();

        assertThat(result.conversationsTouched()).isEqualTo(1);
        assertThat(result.invocationsFilled()).isEqualTo(1);

        Optional<DetailSnapshot> detail =
                findDetail(conv, segment.getId(), toolRow.getId(), "call_1");
        assertThat(detail).isPresent();
        assertThat(detail.get().name()).isEqualTo("updateDocument");
        assertThat(detail.get().argumentsRaw()).isEqualTo("{\"id\":7,\"body\":\"long\"}");
        assertThat(detail.get().resultText()).isEqualTo("the full result");
    }

    @Test
    void isIdempotent() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        newConversation(conv);

        ChatMessageEntity segment =
                save(
                        conv,
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(new ToolData.Call("call_0", "function", "first", "{}")),
                                null));
        messageRepo.save(
                segment.withMeta(
                        new ChatMessageMeta(
                                runId,
                                false,
                                List.of(oldMeta("first", ToolInvocationStatus.OK, 0)))));

        ChatMemoryService.BackfillResult first = memory().backfillToolCallIds();
        ChatMemoryService.BackfillResult second = memory().backfillToolCallIds();

        assertThat(first.invocationsFilled()).isEqualTo(1);
        assertThat(second.invocationsFilled()).isEqualTo(0);
        assertThat(second.conversationsTouched()).isEqualTo(0);
    }

    @Test
    void leavesConversationsWithoutToolDataUntouched() {
        String conv = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        newConversation(conv);

        // Дотуловдатовая эпоха: meta есть, tool_data — нет.
        save(
                conv,
                MessageType.ASSISTANT,
                new ChatMessageMeta(
                        runId, false, List.of(oldMeta("legacyTool", ToolInvocationStatus.OK, 0))),
                null);

        ChatMemoryService.BackfillResult result = memory().backfillToolCallIds();

        assertThat(result.conversationsTouched()).isZero();
        assertThat(result.invocationsFilled()).isZero();
    }

    private record DetailSnapshot(String name, String argumentsRaw, String resultText) {}

    private Optional<DetailSnapshot> findDetail(
            String conv, long messageId, @Nullable Long responseMessageId, String callId) {
        return memory().findToolCallDetail(conv, messageId, responseMessageId, callId)
                .map(d -> new DetailSnapshot(d.name(), d.argumentsRaw(), d.resultText()));
    }
}
