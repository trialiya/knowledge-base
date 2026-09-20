package io.github.trialiya.kb.tools;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A tool of an MCP connection that is down right now: the model is still offered it, and calling it
 * is answered with «that server is unreachable» instead of the call going anywhere.
 *
 * <p>The alternative — dropping the tool from the request while its server is down — changes the
 * tool list, and the tool list is part of the prompt prefix every provider keys its cache by. One
 * server blinking would then cost the cached prefix of every conversation on the instance, twice:
 * once when the tool leaves and once when it comes back. A definition that stays byte-for-byte the
 * same costs nothing, and the model is told the truth on the one turn it actually tries to use the
 * tool.
 *
 * <p>Delegating {@link #getToolDefinition()} is therefore the whole point: name, description and
 * schema are the ones the server last advertised, so the request the model sees does not change
 * when a connection goes down. Only the call behaves differently.
 *
 * <p>Which tools exist at all still follows the server: a tool it stops advertising on a
 * <em>successful</em> probe is gone from the set (see {@code McpToolRegistry}). Unavailability is
 * not deletion — only a server that answered gets to say a tool no longer exists.
 */
@Slf4j
public record UnavailableToolCallback(ToolCallback delegate, String connection)
        implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * Delegated for the same reason the definition is: everything the framework reads off a
     * callback to build the request must be what the server advertised, or the promise above — that
     * the request does not change while a connection is down — would hold only by accident.
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * {@link ToolExecutionException} for the same reason {@code UnknownToolCallbackResolver} uses
     * it: it is the one exception the framework turns into a tool result rather than the end of the
     * run (see {@code ChatConfig}'s {@code ToolExecutionExceptionProcessor}).
     */
    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String name = getToolDefinition().name();
        log.info("Tool '{}' was called while its MCP connection '{}' is down", name, connection);
        throw new ToolExecutionException(
                getToolDefinition(),
                new IllegalStateException(
                        "The tool '"
                                + name
                                + "' belongs to the external MCP server '"
                                + connection
                                + "', which is not reachable right now, so nothing was executed."
                                + " The connection is retried in the background: this tool may"
                                + " work again later in this same conversation. Do not retry it"
                                + " immediately — carry on without it, and say so if the answer"
                                + " depends on it."));
    }
}
