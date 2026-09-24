package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.StoredScriptResult;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The shelf a chat keeps its script results on — what {@code ScriptRunner} writes a finished run's
 * value to, and what {@code kb.result} and {@code saveScriptResult} read back.
 *
 * <p>An interface, with {@link ChatScriptResults} the one implementation, so the sandbox tests can
 * stand a runner up over a map instead of a database.
 */
public interface ScriptResultStore {

    /**
     * Keeps {@code json} as the chat's next result. Never throws: a run has already finished by the
     * time it is kept, and failing to keep it must not turn a result into an error.
     *
     * @param script the name the script was run by; null for an inline script
     * @param project canonical id of the repository the run read
     * @param json the run's value, serialised by the guest's own {@code JSON.stringify}
     */
    Kept keep(String conversationId, @Nullable String script, String project, String json);

    /** The value kept under {@code id} in this chat, as JSON; empty when there is no such id. */
    Optional<String> valueJson(String conversationId, String id);

    /** What this chat still keeps, oldest first. */
    List<StoredScriptResult> list(String conversationId);

    /**
     * The outcome of {@link #keep}: the id the value can be read back by, or why there is none.
     *
     * @param id {@code r<n>}; null when the value was not kept
     * @param note a sentence for the run's log when the reason is something the model can act on
     *     (the value was too large); null when it was kept, or when keeping is simply switched off
     */
    record Kept(@Nullable String id, @Nullable String note) {

        static Kept as(String id) {
            return new Kept(id, null);
        }

        static Kept not(@Nullable String note) {
            return new Kept(null, note);
        }
    }
}
