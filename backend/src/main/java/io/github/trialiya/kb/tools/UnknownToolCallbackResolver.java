package io.github.trialiya.kb.tools;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * Answers a tool name that is not in the request: the model called something it was never given.
 *
 * <p>Without this the run dies. {@code DefaultToolCallingManager} looks the name up among the
 * request's callbacks and, finding nothing, throws {@code IllegalStateException} from inside the
 * stream — the reactive chain is torn down mid-answer, the partial reply is saved, and the history
 * is left with a {@code tool_calls} tail to repair. A name the model invented is a mistake of the
 * same sort as a bad argument, and it gets the same treatment: the call is answered, the model
 * reads what went wrong and picks a tool it actually has. Nothing is executed either way.
 *
 * <p>Reached only because {@code spring.ai.tools.resolution.fallback.enabled=true} — the framework
 * consults a resolver for an unmatched name only under that flag, so the property and this class
 * are one mechanism in two places.
 *
 * <p>It replaces the resolver Spring Boot would contribute, and that is the point: the framework's
 * own scans the context for {@code ToolCallback} beans and {@code ToolCallbackProvider}s, which
 * would make a tool reachable by name that {@code ChatToolset} deliberately left out — an MCP
 * server's tool in a deployment with {@code kb.mcp.enabled=false}, say. Nothing here resolves
 * anything: the assembled toolset stays the only way to reach a tool. Nothing in this application
 * asks for a tool by name either ({@code toolNames} is never set — every caller passes callbacks),
 * so name resolution is a path with no legitimate traffic on it.
 */
@Slf4j
public class UnknownToolCallbackResolver implements ToolCallbackResolver {

    /**
     * Wrapped like every other callback so the invented call shows up in the chat's invocation log
     * rather than only in the protocol history — the plaque is how a user sees what the model
     * tried.
     */
    @Override
    public ToolCallback resolve(String toolName) {
        return new RecordingToolCallback(new UnknownToolCallback(toolName));
    }

    /**
     * Fails every call with the name it was asked for. {@link ToolExecutionException} on purpose:
     * it is the one exception the framework turns into a tool result, through {@code
     * ToolExecutionExceptionProcessor} (see {@code ChatConfig}) — anything else thrown here would
     * escape the tool loop and end the run, which is what this class exists to prevent.
     */
    private record UnknownToolCallback(String toolName) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            // The requested name, not a placeholder: the error message and the log plaque are keyed
            // by it. The definition never reaches the model — it is built after the request.
            return DefaultToolDefinition.builder()
                    .name(toolName)
                    .description("Unknown tool")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            log.warn("Model called a tool it does not have: '{}'", toolName);
            throw new ToolExecutionException(
                    getToolDefinition(),
                    new IllegalArgumentException(
                            "There is no tool named '"
                                    + toolName
                                    + "', and nothing was executed. The tools listed with this"
                                    + " request are the only ones that exist — call one of them by"
                                    + " its exact name, or answer without a tool. Do not retry"
                                    + " this name."));
        }
    }
}
