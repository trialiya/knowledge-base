package io.github.trialiya.kb.model.script;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one {@code runScript} call.
 *
 * <p>A failed run is still a result, not an exception: {@code error} is filled in and the model
 * gets to see how far the script got ({@code log}, {@code stats}) before it broke. The one
 * exception is a user-cancelled run, which never reaches the model at all.
 *
 * @param value the script's return value, converted from JSON; null when it returned nothing
 * @param log lines collected via {@code kb.log}
 * @param stats what the run consumed; see {@link ScriptStats}
 * @param error why it stopped, or null when it completed normally
 * @param filesRead paths the run touched, in first-read order — feeds the file chips in the UI
 */
public record ScriptResult(
        @Nullable Object value,
        List<String> log,
        ScriptStats stats,
        @Nullable ScriptError error,
        List<String> filesRead)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Paths listed in the UI meta; a script may legitimately touch far more than fits a plaque. */
    private static final int META_PATH_LIMIT = 50;

    @Override
    public String getFormattedResponse() {
        return Compact.tag("script")
                .add("files", stats.filesRead())
                .add("bytes", stats.bytesRead())
                .add("calls", stats.calls())
                .add("ms", stats.elapsedMs())
                .add("error", error == null ? null : error.kind())
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filesRead", stats.filesRead());
        meta.put("bytesRead", stats.bytesRead());
        meta.put("calls", stats.calls());
        meta.put("elapsedMs", stats.elapsedMs());
        meta.put("paths", filesRead.stream().limit(META_PATH_LIMIT).toList());
        if (error != null) {
            meta.put("error", error.kind().name());
        }
        return meta;
    }
}
