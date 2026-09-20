package io.github.trialiya.kb.tools;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.ai.tool.ToolCallback;

/**
 * The tools the chat model actually gets, split by where they came from: {@code builtin} are the
 * {@code @Tool} methods of the {@code functions} package, {@code mcp} are whatever the configured
 * MCP servers advertise.
 *
 * <p>Assembled once in {@code ChatConfig}, which is also the only place that decides what is in it
 * — the edit tools, the sub-agent and {@code runScript} each depend on their own opt-in. Everything
 * that needs to know what the model can call reads this bean instead of re-deriving the list, and
 * {@code ToolCatalogService} builds the Settings catalogue from the very same callbacks, so the
 * panel cannot show a tool the model does not have.
 *
 * <p>The built-in half is fixed for the life of the process; the MCP half is read from {@link
 * McpToolRegistry} on every call, because a connection can come up, change its tool list or go away
 * long after startup. Two consequences for callers: {@link #mcp()} and {@link #all()} are snapshots
 * and must not be cached, and a {@code ChatClient} built once can only carry the built-in half —
 * the MCP callbacks are attached per request (see {@code ChatRunService}).
 *
 * <p>Callbacks are handed out already wrapped in {@link RecordingToolCallback} — that wrapping is
 * transparent to {@code getToolDefinition()}, and keeping it here means every reader gets the
 * callbacks the model is actually given, not a parallel list that only looks like them.
 *
 * <p>A dedicated type rather than a {@code List<ToolCallback>} bean: Spring AI resolves tool beans
 * by type, and a bare collection of callbacks in the context would be picked up as an ambient tool
 * source on top of the explicit wiring.
 */
public final class ChatToolset {

    private final List<ToolCallback> builtin;
    private final Supplier<List<ToolCallback>> mcp;

    public ChatToolset(List<ToolCallback> builtin, Supplier<List<ToolCallback>> mcp) {
        this.builtin = List.copyOf(builtin);
        this.mcp = mcp;
    }

    /** A toolset whose MCP half never changes — MCP switched off, and the tests. */
    public ChatToolset(List<ToolCallback> builtin, List<ToolCallback> mcp) {
        this(builtin, () -> mcp);
    }

    /** The {@code @Tool} methods of this application. */
    public List<ToolCallback> builtin() {
        return builtin;
    }

    /**
     * The tools of the MCP connections as they stand right now — including those of a connection
     * that is down, which stay in the set and answer with an error (see {@code
     * UnavailableToolCallback}) so that the model's tool list, and with it the cached prompt
     * prefix, does not move every time a server blinks.
     */
    public List<ToolCallback> mcp() {
        return List.copyOf(mcp.get());
    }

    /**
     * Both halves at this moment, built-ins first — for a {@code ChatClient} built per call ({@code
     * CompactService}). A client built once takes {@link #builtin()} instead and is given {@link
     * #mcp()} per request.
     */
    public ToolCallback[] all() {
        return Stream.concat(builtin.stream(), mcp.get().stream()).toArray(ToolCallback[]::new);
    }
}
