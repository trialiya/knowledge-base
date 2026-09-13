package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.trialiya.kb.config.ChatConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * A tool name the model invented becomes an answer to the model, not the end of the run.
 *
 * <p>The two halves are tested together because neither is enough on its own: the callback has to
 * fail with the one exception type the framework routes to {@link ToolExecutionExceptionProcessor},
 * and that processor — ours, with its own rethrow list (see {@code ChatConfig}) — has to turn it
 * into a result rather than let it out. A plain {@code IllegalArgumentException} thrown from a
 * callback, the natural way to write this, escapes the tool loop instead and kills the stream
 * exactly as the framework's own {@code IllegalStateException} did.
 */
class UnknownToolCallbackResolverTest {

    private final ToolCallbackResolver resolver = new UnknownToolCallbackResolver();

    private final ToolExecutionExceptionProcessor processor =
            new ChatConfig().toolExecutionExceptionProcessor(new ToolCallingProperties());

    @Test
    void theRequestedNameIsAnsweredWithAnErrorTheModelCanActOn() {
        ToolCallback callback = resolver.resolve("runScript");

        Throwable failure = catchThrowable(() -> callback.call("{\"script\":\"return 1\"}", null));

        assertThat(failure).isInstanceOf(ToolExecutionException.class);
        assertThat(processor.process((ToolExecutionException) failure))
                .contains("runScript")
                .contains("no tool named");
    }

    /** The plaque in chat is keyed by the definition's name, so it has to be the invented one. */
    @Test
    void theCallbackCarriesTheNameItWasAskedFor() {
        assertThat(resolver.resolve("noSuchTool").getToolDefinition().name())
                .isEqualTo("noSuchTool");
    }
}
