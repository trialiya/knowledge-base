package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.McpProperties.ScriptAccess;
import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.tools.McpToolRegistry;
import io.github.trialiya.kb.tools.McpToolRegistry.ScriptTool;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Whether a script run gets an {@code mcp} object, and which tools it may call through it.
 *
 * <p>Three things have to agree: the run's kind ({@link ScriptMcpScope}, the caller's to say), the
 * feature switch ({@code kb.script.mcp.enabled}), and the connection's own entry in {@code
 * kb.mcp.script-access} — a list of tool globs, plus whether scheduled runs may use them. A
 * connection with no entry gives scripts nothing: which of a server's tools only read is known to
 * whoever connected it, not to the server's own {@code readOnlyHint}.
 *
 * <p>The tool list is read off {@link McpToolRegistry} on every call rather than once per run: a
 * connection can come up or go down while a script runs, and a script should see the same state
 * the model's own tool list would.
 */
@Service
public class ScriptMcp {

    private final ScriptMcpProperties properties;
    private final McpProperties mcpProperties;
    private final Supplier<@Nullable List<ScriptTool>> registry;

    /** {@code kb.mcp.script-access}, its globs compiled once rather than on every call. */
    private final Map<String, Access> access;

    private record Access(List<Pattern> tools, boolean scheduled) {}

    public ScriptMcp(
            ScriptMcpProperties properties, McpProperties mcpProperties, ObjectProvider<McpToolRegistry> registry) {
        this(properties, mcpProperties, () -> {
            McpToolRegistry current = registry.getIfAvailable();
            return current == null ? null : current.scriptTools();
        });
    }

    /**
     * @param registry {@link McpToolRegistry#scriptTools()}, or null when there is no registry —
     *     MCP switched off, from either side (see {@code ChatConfig#mcpToolRegistry})
     */
    ScriptMcp(
            ScriptMcpProperties properties,
            McpProperties mcpProperties,
            Supplier<@Nullable List<ScriptTool>> registry) {
        this.properties = properties;
        this.mcpProperties = mcpProperties;
        this.registry = registry;
        this.access = mcpProperties.scriptAccess().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> compile(entry.getValue())));
    }

    private static Access compile(ScriptAccess access) {
        return new Access(access.tools().stream().map(ScriptMcp::glob).toList(), access.scheduled());
    }

    /** No run gets an {@code mcp} object — the sandbox tests that are not about MCP. */
    static ScriptMcp none() {
        return new ScriptMcp(
                ScriptMcpProperties.disabled(),
                new McpProperties(false, 60_000, Map.of(), Map.of(), 0, Map.of(), Map.of()),
                () -> null);
    }

    /**
     * Whether any chat run can get an {@code mcp} object in this deployment — what decides whether
     * the handbook describes it. Not whether a tool is up right now: that changes by the minute, and
     * the handbook is rendered once. The registry's presence does not change, and it is what says
     * MCP is switched off from the starter's side ({@code
     * spring.ai.mcp.client.toolcallback.enabled}) as well as from ours.
     */
    public boolean offered() {
        return properties.enabled() && mcpProperties.enabled() && !access.isEmpty() && registry.get() != null;
    }

    /**
     * The {@code mcp} object for one run, or null when this run gets none.
     *
     * @param deadlineNanos the run's own deadline ({@link System#nanoTime()} scale), past which no
     *     call is waited for
     */
    @Nullable
    McpScriptApi open(ScriptMcpScope scope, ScriptSession session, long deadlineNanos, RunCancellation cancellation) {
        if (scope == ScriptMcpScope.NONE || !offered()) {
            return null;
        }
        return new McpScriptApi(() -> allowed(scope), properties, session, deadlineNanos, cancellation);
    }

    /** The registry's tools that {@code kb.mcp.script-access} lets a run of this kind call. */
    List<ScriptTool> allowed(ScriptMcpScope scope) {
        List<ScriptTool> current = registry.get();
        if (current == null) {
            return List.of();
        }
        return current.stream()
                .filter(tool -> permits(access.get(tool.connection()), scope, tool.name()))
                .toList();
    }

    private static boolean permits(@Nullable Access access, ScriptMcpScope scope, String tool) {
        if (access == null || (scope == ScriptMcpScope.SCHEDULED && !access.scheduled())) {
            return false;
        }
        return access.tools().stream().anyMatch(glob -> glob.matcher(tool).matches());
    }

    /** {@code *} — any run of characters, {@code ?} — one; everything else literal. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.strip().toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
