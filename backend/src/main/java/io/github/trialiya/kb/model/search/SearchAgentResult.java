package io.github.trialiya.kb.model.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.Map;

/**
 * Result of one {@code searchCodebase} sub-agent run.
 *
 * <p>The model consumes {@code report} (the findings text), {@code complete} (whether the search
 * finished on its own vs. was cut short by the iteration cap or an error) and {@code iterations}.
 * {@code durationMs} is operational only — hidden from the model payload but surfaced, together
 * with the others, in the request-scoped invocation log via {@link ToolCallResultMetaProvider} /
 * {@link ToolCallResponseItem} (see {@code RecordingToolCallback}).
 *
 * @param report the compact findings report (citations as {@code path:line})
 * @param complete {@code true} if the sub-agent produced the report on its own; {@code false} when
 *     the iteration budget was exhausted or a degraded path forced an early summary
 * @param iterations number of tool-call rounds the sub-agent executed
 * @param durationMs wall-clock time of the whole run, in milliseconds
 */
public record SearchAgentResult(
        String report, boolean complete, int iterations, @JsonIgnore long durationMs)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Short, human-readable gist for the invocation log. */
    @Override
    public String getFormattedResponse() {
        String head = (complete ? "" : "⚠ неполно • ") + iterations + " шаг(ов)";
        return head + " • " + Compact.truncate(report, 160);
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "complete", complete,
                "iterations", iterations,
                "durationMs", durationMs,
                "reportChars", report == null ? 0 : report.length());
    }
}
