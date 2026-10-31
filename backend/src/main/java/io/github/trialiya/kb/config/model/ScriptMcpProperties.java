package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Whether scripts may call MCP tools ({@code mcp.call}), and what one run may spend on them — see
 * {@code McpScriptApi}. Which tools is not decided here but per connection, in {@code
 * kb.mcp.script-access} ({@link McpProperties#scriptAccess()}).
 *
 * <p>A properties class of its own rather than more {@link ScriptProperties.Limits}: that record is
 * built positionally all over the tests, and the sandbox's own budgets meter local reads two orders
 * of magnitude cheaper than a round trip to an external server.
 *
 * @param enabled the switch for the whole feature; off, no run gets an {@code mcp} object and the
 *     handbook does not mention it
 * @param maxCalls MCP calls one run may make; every call counts, a repeat included — a tool may
 *     have side effects, so nothing is answered from a cache
 * @param maxBytes total size of the answers one run may take in, normalized ({@code McpContent})
 */
@ConfigurationProperties(prefix = "kb.script.mcp")
public record ScriptMcpProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("20") int maxCalls,
        @DefaultValue("16MB") DataSize maxBytes) {

    /** A non-positive budget is a misconfiguration, not a way to switch the feature off. */
    public ScriptMcpProperties {
        if (maxCalls <= 0 || maxBytes.toBytes() <= 0) {
            throw new IllegalArgumentException("kb.script.mcp.max-calls and max-bytes must be positive, got "
                    + maxCalls
                    + " and "
                    + maxBytes
                    + "; to call nothing, set kb.script.mcp.enabled=false");
        }
    }
}
