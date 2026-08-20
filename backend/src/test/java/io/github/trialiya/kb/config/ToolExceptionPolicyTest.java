package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.service.chat.script.ScriptCancelledException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * How a throwing tool is answered.
 *
 * <p>Spring AI's default is to hand the model the exception message as the tool's result, which is
 * right for a failure and wrong for a cancellation: the run a cancelled script belonged to is
 * already disposed, so answering it restarts a conversation the user stopped. {@code ScriptRunner}
 * throws {@link ScriptCancelledException} on the strength of this configuration, so the two have to
 * be pinned together — a default processor here would silently turn "stop" back into "carry on".
 */
class ToolExceptionPolicyTest {

    private static final ToolDefinition TOOL =
            DefaultToolDefinition.builder()
                    .name("runScript")
                    .description("test")
                    .inputSchema("{}")
                    .build();

    private final ToolExecutionExceptionProcessor processor =
            new ChatConfig().toolExecutionExceptionProcessor(new ToolCallingProperties());

    @Test
    void aCancelledScriptStaysAnExceptionInsteadOfBecomingAToolResult() {
        ScriptCancelledException cancelled = new ScriptCancelledException("stopped");

        assertThatThrownBy(() -> processor.process(new ToolExecutionException(TOOL, cancelled)))
                .isSameAs(cancelled);
    }

    @Test
    void anOrdinaryToolFailureIsStillReportedToTheModel() {
        // Unchanged from the framework default: the model reads what went wrong and tries again.
        String result =
                processor.process(
                        new ToolExecutionException(TOOL, new IllegalArgumentException("no such")));

        assertThat(result).contains("no such");
    }
}
