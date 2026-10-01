package io.github.trialiya.kb.service.chat.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Turns what a finished script returned into the two things that outlive the run: the value the
 * model is shown, and — for a run that belongs to a chat — the same value kept whole under an id a
 * later script can read.
 *
 * <p>The two differ on purpose. What reaches the model is capped at {@code
 * kb.script.limits.max-result-chars}, because it lands in its context; what is kept is not, because
 * it only ever reaches another script. That is the point of keeping it: a large intermediate result
 * goes from one script to the next without passing through the model at all.
 */
@Slf4j
final class ScriptResultKeeper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ScriptResultStore store;
    private final ScriptProperties properties;

    ScriptResultKeeper(ScriptResultStore store, ScriptProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * What the run delivers: the result's value, and the model's copy where it differs.
     *
     * @param value the copy the result carries — parsed JSON, or the raw text when truncation broke
     *     it; what the model gets unless {@code shown} is set, and what the call's detail view shows
     *     as the whole result. Bounded by {@code max-result-chars} either way: only {@code
     *     kb.result(id)} has the value past it, and only when it was kept
     * @param resultId the id it is kept under; null when it was not kept
     * @param shown the model's copy when the call's {@code resultLimit} cut the value; null when
     *     nothing was cut
     */
    record Delivered(@Nullable Object value, @Nullable String resultId, ScriptResult.@Nullable Shown shown) {}

    /**
     * @param json the value as the guest's {@code JSON.stringify} wrote it, whole; null when the
     *     script returned nothing
     */
    Delivered deliver(@Nullable String json, ScriptRequest request, String project, ScriptSession session) {
        if (json == null) {
            return new Delivered(null, null, null);
        }
        String resultId = keep(json, request, project, session);
        int max = properties.limits().maxResultChars();
        // Parsed whole only when the limit needs the tree; otherwise only what the model is shown.
        Object whole = request.resultLimit() > 0 || json.length() <= max ? parse(json) : null;
        ScriptResult.Shown shown = shown(whole, request.resultLimit(), resultId, session);
        if (json.length() <= max) {
            return new Delivered(whole, resultId, shown);
        }
        if (shown != null) {
            // The model got the first elements as clean JSON, so the warning about the character
            // cut goes only to the full copy's log — in the model's it would contradict the value
            // in front of it.
            String fullCut = "Full response truncated: maxResultChars="
                    + max
                    + ", but the returned value was "
                    + json.length()
                    + " characters, so this copy shows only its first "
                    + max
                    + ". "
                    + (resultId == null
                            ? "The rest was not kept."
                            : "The whole value is kept as " + resultId + ": kb.result('" + resultId + "').");
            return new Delivered(
                    parse(json.substring(0, max)),
                    resultId,
                    new ScriptResult.Shown(shown.value(), shown.truncated(), fullCut));
        }
        session.log("Result truncated: maxResultChars="
                + max
                + ", but the returned value was "
                + json.length()
                + " characters. "
                + (resultId == null
                        ? "Return a summary (counts, top-N) instead of raw content next" + " time."
                        : "The whole value is kept as "
                                + resultId
                                + ": read it in a later script with kb.result('"
                                + resultId
                                + "'), or save it to a file with saveScriptResult."));
        return new Delivered(parse(json.substring(0, max)), resultId, null);
    }

    /**
     * The model's copy under {@code resultLimit}: the whole value cut by elements first, and only
     * then bounded by {@code max-result-chars} — the other way round the character cut would break
     * the JSON before there was anything left to count. Null when there is no limit or nothing was
     * longer than it.
     */
    private ScriptResult.@Nullable Shown shown(
            @Nullable Object whole, int limit, @Nullable String resultId, ScriptSession session) {
        if (limit <= 0) {
            return null;
        }
        ResultLimit.Trimmed trimmed = ResultLimit.apply(whole, limit);
        if (trimmed.cut().isEmpty()) {
            return null;
        }
        ScriptResult.Truncated truncated = ScriptResult.Truncated.of(limit, trimmed.cut(), resultId);
        String text = toJson(trimmed.value());
        int max = properties.limits().maxResultChars();
        if (text.length() <= max) {
            return new ScriptResult.Shown(trimmed.value(), truncated);
        }
        session.log("Result truncated: even cut to resultLimit="
                + limit
                + " the value is "
                + text.length()
                + " characters, over maxResultChars="
                + max
                + " — you see its first "
                + max
                + ". Pass a smaller resultLimit, or return fewer fields per item.");
        return new ScriptResult.Shown(parse(text.substring(0, max)), truncated);
    }

    private static String toJson(@Nullable Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A parsed script value no longer serialises", e);
        }
    }

    private @Nullable String keep(String json, ScriptRequest request, String project, ScriptSession session) {
        ResultScope scope = request.results();
        // A literal null is what a script that returned null (rather than nothing) produces —
        // there is nothing in it for a later script to read.
        if (scope == null || !scope.keep() || "null".equals(json)) {
            return null;
        }
        ScriptRunSource source = request.source().report();
        ScriptResultStore.Kept kept =
                store.keep(scope.conversationId(), source == null ? null : source.name(), project, json);
        if (kept.note() != null) {
            session.log(kept.note());
        }
        return kept.id();
    }

    private static @Nullable Object parse(String text) {
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("Script returned a value that is not valid JSON", e);
            return text;
        }
    }
}
