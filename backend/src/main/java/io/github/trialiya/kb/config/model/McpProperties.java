package io.github.trialiya.kb.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for external MCP (Model Context Protocol) tool servers, bound from:
 *
 * <pre>
 * kb:
 *   mcp:
 *     enabled: true
 *     retry-interval-ms: 60000
 *     bearer-tokens:          # connection name -> Bearer token, for authenticated remote servers
 *       jira: ${JIRA_MCP_TOKEN}
 *     headers:                # connection name -> arbitrary extra headers, for servers that need
 *       jira:                 # more than a bearer token (e.g. a second auth/tenant header)
 *         X-Atlassian-Cloud-Id: ${JIRA_CLOUD_ID}
 *     max-result-chars: 20000 # how much of one tool answer reaches the model; 0 — all of it
 *     result-limits:          # connection name -> its own limit
 *       confluence: 60000
 *     script-access:          # connection name -> what scripts may call (mcp.call)
 *       jira:
 *         tools: [jira_search, jira_get_*]
 *         scheduled: true     # scheduled scripts too
 * </pre>
 *
 * <p>{@code enabled} gates whether tools from connected MCP servers (see {@code
 * spring.ai.mcp.client.*}) are connected to at all and merged into the chat model's tool list — see
 * {@code ChatConfig.mcpToolRegistry}. {@code bearerTokens} and {@code headers} keys match the
 * connection names under {@code spring.ai.mcp.client.sse.connections} / {@code
 * .streamable-http.connections}; {@code bearerTokens} is applied as an {@code Authorization: Bearer
 * <token>} header and {@code headers} lets a connection send any additional header(s) a server
 * requires on top of (or instead of) that — see {@code McpClientConfig}.
 *
 * <p>{@code retryIntervalMs} is how often every connection — the ones that are up included — is
 * probed again ({@code McpToolRegistry}). It also lives as a raw placeholder in that class's {@code
 * fixedDelayString} — an annotation cannot read a bean — and is bound here as well so the Settings
 * panel reports the same number the scheduler runs on.
 *
 * <p>{@code maxResultChars} and {@code resultLimits} bound what one tool answer brings into the
 * model's context; past them the answer is kept whole for a script and the model is shown its head
 * — see {@code McpResultLimitCallback}. A non-positive value is "no limit", not a
 * misconfiguration: it is how a deployment asks for every answer as the server sent it.
 *
 * <p>{@code scriptAccess} is what a script may call through {@code mcp.call}, per connection; a
 * connection not listed gives scripts nothing. It lives here rather than under {@code kb.script}
 * because it is a property of the server — which of its tools only read and which write — and the
 * one who knows that is whoever connected it. A server's own {@code readOnlyHint} is not consulted:
 * the server sets it about itself. Scripts reach these tools only with {@code
 * kb.script.mcp.enabled} on as well.
 */
@ConfigurationProperties(prefix = "kb.mcp")
public record McpProperties(
        boolean enabled,
        long retryIntervalMs,
        Map<String, String> bearerTokens,
        Map<String, Map<String, String>> headers,
        @DefaultValue("20000") int maxResultChars,
        Map<String, Integer> resultLimits,
        Map<String, ScriptAccess> scriptAccess) {

    public McpProperties {
        bearerTokens = bearerTokens == null ? Map.of() : bearerTokens;
        headers = headers == null ? Map.of() : headers;
        resultLimits = withoutBlanks(resultLimits);
        scriptAccess = withoutBlanks(scriptAccess);
    }

    /**
     * {@code map} without its blank entries: a connection key left without a value ({@code
     * confluence:}, or an empty environment variable behind it) binds as a null value, which means
     * "nothing of its own" — not a startup failure in {@code Map.copyOf} far from the property.
     */
    private static <V> Map<String, V> withoutBlanks(@Nullable Map<String, V> map) {
        if (map == null) {
            return Map.of();
        }
        Map<String, V> kept = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (value != null) {
                kept.put(key, value);
            }
        });
        return Collections.unmodifiableMap(kept);
    }

    /**
     * What scripts may call from one connection.
     *
     * @param tools globs over tool names ({@code *} any run of characters, {@code ?} one) as the
     *     model sees them; empty — nothing
     * @param scheduled whether a scheduled script may call them too — it runs with nobody watching
     */
    public record ScriptAccess(List<String> tools, boolean scheduled) {

        public ScriptAccess {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
}
