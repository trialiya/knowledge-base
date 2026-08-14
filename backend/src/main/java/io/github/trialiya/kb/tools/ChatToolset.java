package io.github.trialiya.kb.tools;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.ai.tool.ToolCallback;

/**
 * The tools the chat model actually gets, split by where they came from: {@code builtin} are the
 * {@code @Tool} methods of the {@code functions} package, {@code mcp} are whatever the configured
 * MCP servers advertise.
 *
 * <p>Assembled once in {@code ChatConfig}, which is also the only place that decides what is in it
 * — the edit tools, the sub-agent and {@code runScript} each depend on their own opt-in. Everything
 * that needs to know what the model can call reads this bean instead of re-deriving the list: the
 * {@code ChatClient} gets {@link #all()}, and {@code ToolCatalogService} builds the Settings
 * catalogue from the same callbacks, so the panel cannot show a tool the model does not have.
 *
 * <p>Callbacks are stored already wrapped in {@link RecordingToolCallback} — that wrapping is
 * transparent to {@code getToolDefinition()}, and keeping it here means {@link #all()} is exactly
 * what the client is built with.
 *
 * <p>A dedicated type rather than a {@code List<ToolCallback>} bean: Spring AI resolves tool beans
 * by type, and a bare collection of callbacks in the context would be picked up as an ambient tool
 * source on top of the explicit wiring.
 */
public record ChatToolset(List<ToolCallback> builtin, List<ToolCallback> mcp) {

    public ChatToolset {
        builtin = List.copyOf(builtin);
        mcp = List.copyOf(mcp);
    }

    /** Every callback handed to the {@code ChatClient}, built-ins first. */
    public ToolCallback[] all() {
        return Stream.concat(builtin.stream(), mcp.stream()).toArray(ToolCallback[]::new);
    }
}
