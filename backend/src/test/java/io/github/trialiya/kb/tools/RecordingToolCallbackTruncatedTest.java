package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

/**
 * The summary of a call is counted from the answer without its {@link ToolResult} wrapper — and
 * the wrapper's {@code truncated} must survive that, or a cut search reads like the whole answer.
 */
class RecordingToolCallbackTruncatedTest {

    private ToolInvocationCollector collector;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        collector = new ToolInvocationCollector();
        context = new ToolContext(Map.of(ToolInvocationCollector.KEY, collector));
    }

    @Test
    void aCutAnswerSaysSoInItsGist() {
        callback("cut").call("{}", context);
        callback("whole").call("{}", context);

        List<ToolInvocation> recorded = collector.completedSnapshot();
        assertThat(recorded.get(0).resultGist()).contains("truncated");
        assertThat(recorded.get(1).resultGist()).doesNotContain("truncated");
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = Stream.of(ToolCallbacks.from(new Tools()))
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
        return new RecordingToolCallback(callback);
    }

    public static class Tools {

        @Tool(description = "cut list", resultConverter = CompactToolResultConverter.class)
        public ToolResult<List<String>> cut() {
            return new ToolResult<>("kb", List.of("a"), true);
        }

        @Tool(description = "whole list", resultConverter = CompactToolResultConverter.class)
        public ToolResult<List<String>> whole() {
            return new ToolResult<>("kb", List.of("a"), false);
        }
    }
}
