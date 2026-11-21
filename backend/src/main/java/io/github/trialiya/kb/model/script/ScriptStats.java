package io.github.trialiya.kb.model.script;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What one script run actually consumed. Returned to the model (so it can see it is approaching a
 * budget) and surfaced in the tool-call plaque, which is the only way a user can tell how much of
 * the repository a script touched.
 *
 * @param filesRead distinct files read via {@code kb.read} / {@code kb.outline}
 * @param bytesRead total bytes those reads returned
 * @param calls {@code kb.*} calls that actually did work — a repeat with the same arguments as an
 *     earlier call in this run is answered from the run's cache and does not add to this count (see
 *     {@code ScriptSession#call})
 * @param filesEdited files created or modified — zero for a read-only run
 * @param elapsedMs wall-clock duration of the run
 * @param mcpCalls calls to MCP tools ({@code mcp.call}); left out of the JSON when there were none,
 *     which is every run where scripts have no {@code mcp} object
 */
public record ScriptStats(
        int filesRead,
        long bytesRead,
        int calls,
        int filesEdited,
        long elapsedMs,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int mcpCalls) {

    /** A run that called no MCP tool. */
    public ScriptStats(int filesRead, long bytesRead, int calls, int filesEdited, long elapsedMs) {
        this(filesRead, bytesRead, calls, filesEdited, elapsedMs, 0);
    }
}
