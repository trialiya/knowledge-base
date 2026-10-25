package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.McpProperties.ScriptAccess;
import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.tools.McpToolRegistry;
import io.github.trialiya.kb.tools.McpToolRegistry.ScriptTool;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class ScriptMcp {

    private final ScriptMcpProperties properties;
    private final Supplier<@Nullable List<ScriptTool>> registry;

    /** {@code kb.mcp.script-access}, its globs compiled once rather than on every call. */
    private final Map<String, Access> access;

    private record Access(List<Pattern> tools, boolean scheduled) {}

    public ScriptMcp(
            ScriptMcpProperties properties, McpProperties mcpProperties, ObjectProvider<McpToolRegistry> registry) {
        this(properties, configured(mcpProperties.scriptAccess(), registry.getIfAvailable()), () -> {
            McpToolRegistry current = registry.getIfAvailable();
            return current == null ? null : current.scriptTools();
        });
    }

    /**
     * @param scriptAccess {@code kb.mcp.script-access}; an entry that lists no tools is dropped, so
     *     it does not make the feature look {@linkplain #offered() offered}
     * @param registry {@link McpToolRegistry#scriptTools()}, or null when there is no registry —
     *     MCP switched off, from either side (see {@code ChatConfig#mcpToolRegistry})
     */
    ScriptMcp(
            ScriptMcpProperties properties,
            Map<String, ScriptAccess> scriptAccess,
            Supplier<@Nullable List<ScriptTool>> registry) {
        this.properties = properties;
        this.registry = registry;
        this.access = scriptAccess.entrySet().stream()
                .filter(entry -> !entry.getValue().tools().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> compile(entry.getValue())));
    }

    /**
     * The entries of {@code scriptAccess} that name a configured connection. A misspelt key would
     * otherwise put the {@code mcp} section in every handbook for an object whose tool list is
     * always empty — so it is left out and said once, at startup, where the deployment looks.
     */
    private static Map<String, ScriptAccess> configured(
            Map<String, ScriptAccess> scriptAccess, @Nullable McpToolRegistry registry) {
        if (registry == null || scriptAccess.isEmpty()) {
            return scriptAccess;
        }
        Set<String> connections = registry.statuses().stream()
                .map(McpToolRegistry.ConnectionStatus::name)
                .collect(Collectors.toSet());
        Map<String, ScriptAccess> known = new LinkedHashMap<>();
        scriptAccess.forEach((connection, access) -> {
            if (connections.contains(connection)) {
                known.put(connection, access);
            } else {
                log.warn(
                        "kb.mcp.script-access.{} names no configured MCP connection (configured: {});"
                                + " scripts get nothing from it",
                        connection,
                        connections);
            }
        });
        return known;
    }

    private static Access compile(ScriptAccess access) {
        return new Access(access.tools().stream().map(ScriptMcp::glob).toList(), access.scheduled());
    }

    /** {@code kb.script.mcp.*} — the budgets the handbook quotes. */
    ScriptMcpProperties properties() {
        return properties;
    }

    /**
     * Whether any chat run can get an {@code mcp} object in this deployment — what decides whether
     * the handbook describes it. Not whether a tool is up right now: that changes by the minute, and
     * the handbook is rendered once. The registry's presence does not change, and it is what says
     * MCP is switched off — by {@code kb.mcp.enabled} or by the starter's own {@code
     * spring.ai.mcp.client.toolcallback.enabled}.
     */
    public boolean offered() {
        return properties.enabled() && !access.isEmpty() && registry.get() != null;
    }

    /**
     * The {@code mcp} object for one run, or null when this run gets none.
     *
     * @param deadlineNanos the run's own deadline ({@link System#nanoTime()} scale), past which no
     *     call is waited for
     */
    @Nullable
    McpScriptApi open(ScriptMcpScope scope, ScriptSession session, long deadlineNanos, RunCancellation cancellation) {
        if (scope == ScriptMcpScope.NONE || !offered() || !admits(scope)) {
            return null;
        }
        return new McpScriptApi(() -> allowed(scope), properties, session, deadlineNanos, cancellation);
    }

    /**
     * Whether any entry lets a run of this kind call something — what decides whether it gets an
     * {@code mcp} object at all: a scheduled run with no {@code scheduled: true} connection would
     * get one that refuses every call, and an unavailable thing is absent, not refusing.
     */
    private boolean admits(ScriptMcpScope scope) {
        return scope != ScriptMcpScope.SCHEDULED || access.values().stream().anyMatch(Access::scheduled);
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
