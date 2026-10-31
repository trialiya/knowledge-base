package io.github.trialiya.kb.tools;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

/**
 * How much of an MCP tool's answer reaches the model, per connection, and where the rest goes — see
 * {@link McpResultLimitCallback}.
 *
 * @param maxResultChars the limit of a connection with none of its own; 0 or less — no limit
 * @param perConnection connection name → its own limit, overriding {@code maxResultChars}
 * @param shelf where a cut answer is kept whole; null when nothing could read it back (scripts or
 *     kept results switched off), and then a cut answer is simply cut
 * @param fullTextMaxChars the largest answer the call's detail view is given whole; a larger one
 *     shows only what the model saw
 */
public record McpResultLimits(
        int maxResultChars,
        Map<String, Integer> perConnection,
        @Nullable ToolResultShelf shelf,
        int fullTextMaxChars) {

    public McpResultLimits {
        perConnection = Map.copyOf(perConnection);
    }

    /** Every answer as it came — MCP switched off, and the tests that are not about limits. */
    public static McpResultLimits none() {
        return new McpResultLimits(0, Map.of(), null, 0);
    }

    /** The limit {@code connection}'s answers are held to; 0 or less — none. */
    public int limitFor(String connection) {
        return perConnection.getOrDefault(connection, maxResultChars);
    }

    /** {@code tool} held to its connection's limit, or {@code tool} itself when there is none. */
    public ToolCallback wrap(ToolCallback tool, String connection) {
        int limit = limitFor(connection);
        return limit > 0 ? new McpResultLimitCallback(tool, limit, shelf, fullTextMaxChars) : tool;
    }
}
