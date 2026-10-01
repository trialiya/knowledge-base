package io.github.trialiya.kb.model.script;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.tool.ModelView;
import io.github.trialiya.kb.model.tool.ProjectScoped;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one {@code runScript} call.
 *
 * <p>A failed run is still a result, not an exception: {@code error} is filled in and the model
 * gets to see how far the script got ({@code log}, {@code stats}) before it broke. The one
 * exception is a user-cancelled run, which never reaches the model at all.
 *
 * @param project id of the repository the script ran against — obligatory in the response because
 *     {@code runScript} can target a project other than the chat's active one (see {@code
 *     ScriptFunction#runScript}); without it the model cannot tell which repository {@code
 *     filesRead} and {@code edits} belong to
 * @param resultId the id this run's value is kept under in the chat ({@code r3}), for a later
 *     script's {@code kb.result} and for {@code saveScriptResult}; null when nothing was kept — a
 *     failed run, a run outside any chat, a value over {@code kb.script.results.max-chars}
 * @param source where the script came from when it was not written in the call — a saved script of
 *     the project, an attachment. Null for a script the model wrote inline: there the call's own
 *     argument is the text, and repeating a name it does not have would say nothing
 * @param value the script's return value, converted from JSON; null when it returned nothing
 * @param log lines collected via {@code kb.log}
 * @param stats what the run consumed; see {@link ScriptStats}
 * @param error why it stopped, or null when it completed normally
 * @param filesRead paths the run touched, in first-read order — feeds the file chips in the UI
 * @param edits files the run created or modified, with a unified diff each; always empty for a
 *     failed run, which writes nothing at all
 * @param shown what the model is shown in place of {@code value} when the call's {@code
 *     resultLimit} cut it; null — the model gets {@code value} as it is. Not part of the result:
 *     only {@link #forModel} reads it. Its {@link Shown#fullCut} note, when there is one, ends
 *     {@code log} here and is left out of the model's log
 */
public record ScriptResult(
        String project,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String resultId,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) ScriptRunSource source,
        @Nullable Object value,
        List<String> log,
        ScriptStats stats,
        @Nullable ScriptError error,
        List<String> filesRead,
        List<GitEditResult> edits,
        @Nullable @JsonIgnore Shown shown)
        implements ProjectScoped, ToolCallResponseItem, ToolCallResultMetaProvider, ModelView {

    /** The full result's log ends with {@link Shown#fullCut}; {@link #forModel} drops that line. */
    public ScriptResult {
        if (shown != null && shown.fullCut() != null) {
            log = Stream.concat(log.stream(), Stream.of(shown.fullCut())).toList();
        }
    }

    /** Paths listed in the UI meta; a script may legitimately touch far more than fits a plaque. */
    private static final int META_PATH_LIMIT = 50;

    /**
     * Paths of {@code filesRead} the model is shown. The list is there for the user — the call's
     * detail view shows it whole; to the model a long list is context spent on nothing, and the
     * count is in {@code stats} anyway.
     */
    public static final int MODEL_PATH_LIMIT = 5;

    /** A result whose value the model is shown as it is. */
    public ScriptResult(
            String project,
            @Nullable String resultId,
            @Nullable ScriptRunSource source,
            @Nullable Object value,
            List<String> log,
            ScriptStats stats,
            @Nullable ScriptError error,
            List<String> filesRead,
            List<GitEditResult> edits) {
        this(project, resultId, source, value, log, stats, error, filesRead, edits, null);
    }

    /**
     * The fields of the result in the same order, with {@code filesRead} cut to {@link
     * #MODEL_PATH_LIMIT} paths ({@code filesReadMore} says how many were left out) and, when the
     * call's {@code resultLimit} cut the value, {@link #shown} in its place ({@code truncated} says
     * what was cut and where the whole value is).
     */
    @Override
    public ForModel forModel() {
        int more = filesRead.size() - MODEL_PATH_LIMIT;
        return new ForModel(
                project,
                resultId,
                source,
                shown != null ? shown.value() : value,
                shown != null ? shown.truncated() : null,
                shown != null && shown.fullCut() != null ? log.subList(0, log.size() - 1) : log,
                stats,
                error,
                more > 0 ? filesRead.subList(0, MODEL_PATH_LIMIT) : filesRead,
                more > 0 ? more : null,
                edits);
    }

    /**
     * What the model reads: {@link ScriptResult}'s own fields, plus the two notes on what was cut.
     * Public and named as {@link #forModel}'s return type so the native image registers it with
     * the tool's signature (see {@code NativeHints}).
     */
    public record ForModel(
            String project,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String resultId,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            ScriptRunSource source,

            @Nullable Object value,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            Truncated truncated,

            List<String> log,
            ScriptStats stats,
            @Nullable ScriptError error,
            List<String> filesRead,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer filesReadMore,

            List<GitEditResult> edits) {}

    /**
     * The value as the call's {@code resultLimit} left it for the model.
     *
     * @param value the cut value, then bounded by {@code max-result-chars} like any other
     * @param truncated what was cut
     * @param fullCut a line for the full result's log when its {@code value} was itself cut at
     *     {@code max-result-chars}; null when it is whole. Not in the model's log: {@code
     *     truncated} already tells the model what it is missing and where the rest is
     */
    public record Shown(
            @Nullable Object value,
            Truncated truncated,
            @Nullable String fullCut) {

        public Shown(@Nullable Object value, Truncated truncated) {
            this(value, truncated, null);
        }
    }

    /**
     * @param limit the {@code resultLimit} the value was cut to
     * @param cut the JSON path of every cut part → how many elements it had
     * @param note where the rest is, in words — the model reads this, not the Javadoc
     */
    public record Truncated(int limit, Map<String, Integer> cut, String note) {

        public static Truncated of(int limit, Map<String, Integer> cut, @Nullable String resultId) {
            return new Truncated(
                    limit,
                    cut,
                    resultId == null
                            ? "Cut by resultLimit; the rest was not kept — run again without resultLimit to see it."
                            : "Cut by resultLimit; the whole value is kept as "
                                    + resultId
                                    + " — read it with kb.result('"
                                    + resultId
                                    + "') in a later script.");
        }
    }

    @Override
    public String getFormattedResponse() {
        return Compact.tag("script")
                .add("project", project)
                .add("result", resultId)
                .add("script", source == null ? null : source.name())
                .add("files", stats.filesRead())
                .add("bytes", stats.bytesRead())
                .add("calls", stats.calls())
                .add("edited", stats.filesEdited() > 0 ? stats.filesEdited() : null)
                .add("ms", stats.elapsedMs())
                .add("error", error == null ? null : error.kind())
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (resultId != null) {
            meta.put("resultId", resultId);
        }
        if (source != null) {
            meta.put("script", source.name());
            meta.put("scriptPath", source.path());
        }
        meta.put("filesRead", stats.filesRead());
        meta.put("bytesRead", stats.bytesRead());
        meta.put("calls", stats.calls());
        meta.put("elapsedMs", stats.elapsedMs());
        meta.put("paths", filesRead.stream().limit(META_PATH_LIMIT).toList());
        if (!edits.isEmpty()) {
            // Same shape the frontend's file-change block already reads from createFile/editFile.
            meta.put("edits", edits.stream().map(GitEditResult::getResultMeta).toList());
        }
        if (error != null) {
            meta.put("error", error.kind().name());
        }
        return meta;
    }
}
