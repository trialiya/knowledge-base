package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.tool.ModelView;
import io.github.trialiya.kb.model.tool.ProjectScoped;
import io.github.trialiya.kb.model.tool.ToolInvocation;
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
 * A {@link ModelView} result through the whole call path: the model is answered with the view,
 * the run remembers the whole result — and it is the whole result that counts as having shown the
 * model a file ({@link ToolInvocationCollector#hasSeenFile}).
 */
class RecordingToolCallbackModelViewTest {

    private ToolInvocationCollector collector;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        collector = new ToolInvocationCollector();
        context = new ToolContext(Map.of(ToolInvocationCollector.KEY, collector));
    }

    @Test
    void theModelGetsTheViewAndTheRunKeepsTheWholeResult() {
        String answer = callback("listPaths").call("{}", context);

        assertThat(answer).contains("a.md").doesNotContain("b.md");
        ToolInvocation recorded = collector.completedSnapshot().getFirst();
        assertThat(recorded.resultText()).isEqualTo(answer);
        assertThat(recorded.fullResultText()).contains("a.md", "b.md");
        assertThat(recorded.wholeResultText()).isEqualTo(recorded.fullResultText());
    }

    @Test
    void aPathLeftOutOfTheViewStillCountsAsSeen() {
        callback("listPaths").call("{}", context);

        assertThat(collector.hasSeenFile("b.md", "kb")).isTrue();
        assertThat(collector.hasSeenFile("c.md", "kb")).isFalse();
    }

    @Test
    void aViewThatCutNothingKeepsNoSecondCopy() {
        callback("listOnePath").call("{}", context);

        ToolInvocation recorded = collector.completedSnapshot().getFirst();
        assertThat(recorded.fullResultText()).isNull();
        assertThat(recorded.wholeResultText()).isEqualTo(recorded.resultText());
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = Stream.of(ToolCallbacks.from(new Tools()))
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
        return new RecordingToolCallback(callback);
    }

    /** Shows the model the first path only. */
    public record Paths(String project, List<String> paths) implements ModelView, ProjectScoped {

        @Override
        public Object forModel() {
            return new Paths(project, paths.subList(0, 1));
        }
    }

    public static class Tools {

        @Tool(description = "two paths", resultConverter = CompactToolResultConverter.class)
        public Paths listPaths() {
            return new Paths("kb", List.of("a.md", "b.md"));
        }

        @Tool(description = "one path", resultConverter = CompactToolResultConverter.class)
        public Paths listOnePath() {
            return new Paths("kb", List.of("a.md"));
        }
    }
}
