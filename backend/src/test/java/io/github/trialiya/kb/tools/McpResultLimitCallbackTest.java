package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.tools.ToolResultShelf.Shelved;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the model is answered when an MCP tool says more than its limit — and, as much, what it is
 * answered when it does not: an answer within the limit must reach it byte for byte.
 */
class McpResultLimitCallbackTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int LIMIT = 4000;

    private final List<String> kept = new ArrayList<>();
    private final ToolResultShelf shelf = (conversationId, label, json) -> {
        kept.add(conversationId + "|" + label + "|" + json);
        return new Shelved("r" + kept.size(), null);
    };

    @Test
    void anAnswerWithinTheLimitGoesThroughByteForByte() {
        String answer = textBlock("{\"ok\":true}");

        assertThat(limited(answer, shelf).call("{}", chat("c1"))).isEqualTo(answer);
        assertThat(kept).isEmpty();
    }

    @Test
    void aLargerAnswerIsKeptWholeAndTheModelGetsItsShapeAndHead() {
        String payload = issues(300);

        JsonNode envelope = MAPPER.readTree(limited(textBlock(payload), shelf).call("{}", chat("c1")));

        assertThat(kept).containsExactly("c1|mcp:jira_search|" + payload);
        assertThat(envelope.get("truncated").asBoolean()).isTrue();
        assertThat(envelope.get("resultId").asString()).isEqualTo("r1");
        assertThat(envelope.get("totalChars").asInt()).isEqualTo(payload.length());
        assertThat(envelope.get("head").asString()).startsWith("{\"issues\":[{\"key\":\"KB-0\"");
        assertThat(envelope.get("shape").get("keys").get("issues").get("length").asInt())
                .isEqualTo(300);
        assertThat(envelope.get("note").asString()).contains("kb.result('r1')").contains("saveScriptResult");
        assertThat(MAPPER.writeValueAsString(envelope).length()).isLessThanOrEqualTo(LIMIT);
    }

    /** Prose is shown as prose: escaped into a JSON string, a head would spend the limit on {@code \n}. */
    @Test
    void theHeadOfATextAnswerIsTheTextItself() {
        String prose = "line\n".repeat(2000);

        JsonNode envelope = MAPPER.readTree(limited(textBlock(prose), shelf).call("{}", chat("c1")));

        assertThat(envelope.get("head").asString()).startsWith("line\nline\n");
        assertThat(envelope.has("shape")).isFalse();
    }

    @Test
    void outsideAChatNothingIsKeptAndTheModelIsToldToNarrowTheRequest() {
        JsonNode envelope = MAPPER.readTree(limited(textBlock(issues(300)), shelf).call("{}", null));

        assertThat(kept).isEmpty();
        assertThat(envelope.has("resultId")).isFalse();
        assertThat(envelope.get("note").asString()).contains("not kept").contains("Narrow the request");
    }

    @Test
    void whatTheShelfRefusesItSaysWhy() {
        ToolResultShelf full = (conversationId, label, json) -> new Shelved(null, "Result not kept: too large.");

        JsonNode envelope = MAPPER.readTree(limited(textBlock(issues(300)), full).call("{}", chat("c1")));

        assertThat(envelope.has("resultId")).isFalse();
        assertThat(envelope.get("note").asString()).contains("Result not kept: too large.");
    }

    /** An image is what usually tips an answer over — and once left out, nothing needs cutting. */
    @Test
    void anAnswerThatFitsOnceItsBinaryIsLeftOutIsAnsweredWhole() {
        String answer = "[{\"type\":\"text\",\"text\":\"chart\"},{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\""
                + "A".repeat(LIMIT * 2) + "\"}]";

        String answered = limited(answer, shelf).call("{}", chat("c1"));

        assertThat(answered).contains("\"chart\"").contains("omitted (").doesNotContain("AAAA");
        assertThat(kept).isEmpty();
    }

    @Test
    void theCallsDetailViewIsGivenTheWholeAnswer() {
        ToolInvocationCollector collector = new ToolInvocationCollector();
        ToolContext context = new ToolContext(
                Map.of(ChatMemory.CONVERSATION_ID, "c1", ToolInvocationCollector.KEY, collector));
        String payload = issues(300);

        new RecordingToolCallback(limited(textBlock(payload), shelf)).call("{}", context);

        ToolInvocation recorded = collector.completedSnapshot().get(0);
        assertThat(recorded.fullResultText()).isEqualTo(payload);
        assertThat(recorded.resultText()).contains("\"truncated\":true");
    }

    @Test
    void aServersErrorIsHeldToTheLimitToo() {
        ToolCallback failing = new FakeTool(null) {
            @Override
            public String call(String toolInput, @Nullable ToolContext toolContext) {
                throw new ToolExecutionException(
                        getToolDefinition(), new IllegalStateException("Error calling tool: " + "x".repeat(LIMIT * 3)));
            }
        };

        assertThatThrownBy(() -> new McpResultLimitCallback(failing, LIMIT, shelf, 1_000_000).call("{}", chat("c1")))
                .isInstanceOf(ToolExecutionException.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(LIMIT).contains("error message cut"));
    }

    @Test
    void theLimitsWrapOnlyAConnectionThatHasOne() {
        McpResultLimits limits = new McpResultLimits(LIMIT, Map.of("wiki", 0), shelf, 1_000_000);
        ToolCallback tool = new FakeTool("{}");

        assertThat(limits.wrap(tool, "jira")).isInstanceOf(McpResultLimitCallback.class);
        assertThat(limits.wrap(tool, "wiki")).isSameAs(tool);
        assertThat(McpResultLimits.none().wrap(tool, "jira")).isSameAs(tool);
    }

    private static McpResultLimitCallback limited(String answer, ToolResultShelf shelf) {
        return new McpResultLimitCallback(new FakeTool(answer), LIMIT, shelf, 1_000_000);
    }

    private static ToolContext chat(String conversationId) {
        return new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, conversationId));
    }

    /** The answer as Spring AI's MCP callback writes it: the content blocks, as JSON. */
    private static String textBlock(String text) {
        return MAPPER.writeValueAsString(List.of(Map.of("type", "text", "text", text)));
    }

    private static String issues(int count) {
        StringBuilder json = new StringBuilder("{\"issues\":[");
        for (int i = 0; i < count; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"key\":\"KB-")
                    .append(i)
                    .append("\",\"summary\":\"An issue with a summary\"}");
        }
        return json.append("],\"total\":").append(count).append('}').toString();
    }

    private static class FakeTool implements ToolCallback {

        private final @Nullable String answer;

        FakeTool(@Nullable String answer) {
            this.answer = answer;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("jira_search")
                    .description("search")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            return String.valueOf(answer);
        }
    }
}
