package io.github.trialiya.kb.service.chat.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptProperties;
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
     * The returned value, as the model gets it.
     *
     * @param value the model's copy — parsed JSON, or the raw text when truncation broke it
     * @param resultId the id it is kept under; null when it was not kept
     */
    record Delivered(@Nullable Object value, @Nullable String resultId) {}

    /**
     * @param json the value as the guest's {@code JSON.stringify} wrote it, whole; null when the
     *     script returned nothing
     */
    Delivered deliver(
            @Nullable String json, ScriptRequest request, String project, ScriptSession session) {
        if (json == null) {
            return new Delivered(null, null);
        }
        String resultId = keep(json, request, project, session);
        int max = properties.limits().maxResultChars();
        if (json.length() <= max) {
            return new Delivered(parse(json), resultId);
        }
        session.log(
                "Result truncated: maxResultChars="
                        + max
                        + ", but the returned value was "
                        + json.length()
                        + " characters. "
                        + (resultId == null
                                ? "Return a summary (counts, top-N) instead of raw content next"
                                        + " time."
                                : "The whole value is kept as "
                                        + resultId
                                        + ": read it in a later script with kb.result('"
                                        + resultId
                                        + "'), or save it to a file with saveScriptResult."));
        return new Delivered(parse(json.substring(0, max)), resultId);
    }

    private @Nullable String keep(
            String json, ScriptRequest request, String project, ScriptSession session) {
        ResultScope scope = request.results();
        // A literal null is what a script that returned null (rather than nothing) produces —
        // there is nothing in it for a later script to read.
        if (scope == null || !scope.keep() || "null".equals(json)) {
            return null;
        }
        ScriptRunSource source = request.source().report();
        ScriptResultStore.Kept kept =
                store.keep(
                        scope.conversationId(),
                        source == null ? null : source.name(),
                        project,
                        json);
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
