package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Синтез мет плашек из {@code tool_data} для сегментов без {@code meta.invocations} — оборванные и
 * старые прогоны (см. {@link ToolCallService#invocationsFor}).
 */
class ToolCallServiceTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final ToolCallService service =
            new ToolCallService(
                    mock(ChatMessageRepository.class), mock(ToolCallIndexRepository.class));

    private static ChatMessageEntity entity(
            MessageType type, ChatMessageMeta meta, ToolData toolData) {
        return ToolCallTestSupport.entity(CONV, type, meta, toolData);
    }

    @Test
    void invocationsForSynthesizesFromToolDataWhenMetaAbsent() {
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        null,
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0",
                                                "function",
                                                "searchDocuments",
                                                "{\"q\": \"a\"}"),
                                        new ToolData.Call("id-1", "function", "getUserName", "{}")),
                                null));
        final ChatMessageEntity toolRow =
                entity(
                        MessageType.TOOL,
                        null,
                        new ToolData(
                                null,
                                List.of(
                                        new ToolData.Response(
                                                "id-0", "searchDocuments", "\"found 3 docs\""))));

        final List<ToolInvocationMeta> metas =
                service.invocationsFor(segment, List.of(segment, toolRow));

        // SKIP_TOOLS (getUserName) вырезан, как и в attachRunMeta.
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).name()).isEqualTo("searchDocuments");
        assertThat(metas.get(0).status()).isEqualTo(ToolInvocationStatus.OK);
        assertThat(metas.get(0).hasDetails()).isFalse();
        assertThat(metas.get(0).callIndex()).isNull();
        assertThat(metas.get(0).arguments()).containsEntry("q", "a");
        assertThat(metas.get(0).resultGist()).contains("found 3 docs");
    }

    @Test
    void invocationsForPrefersStoredMeta() {
        final ToolInvocationMeta stored =
                new ToolInvocationMeta(
                        "searchDocuments",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        null,
                        true,
                        0,
                        null,
                        null);
        final ChatMessageEntity segment =
                entity(
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(RUN, false, List.of(stored)),
                        new ToolData(
                                List.of(
                                        new ToolData.Call(
                                                "id-0", "function", "searchDocuments", "{}")),
                                null));

        assertThat(service.invocationsFor(segment, List.of(segment))).containsExactly(stored);
    }

    @Test
    void invocationsForNullForPlainMessages() {
        assertThat(service.invocationsFor(entity(MessageType.ASSISTANT, null, null), List.of()))
                .isNull();
        assertThat(
                        service.invocationsFor(
                                entity(
                                        MessageType.TOOL,
                                        null,
                                        new ToolData(
                                                null,
                                                List.of(
                                                        new ToolData.Response(
                                                                "id-0", "a", "\"x\"")))),
                                List.of()))
                .isNull();
    }
}
