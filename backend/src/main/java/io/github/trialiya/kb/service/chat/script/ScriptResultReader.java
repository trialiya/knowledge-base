package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.StoredScriptResult;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What {@code kb.result} / {@code kb.results} — and {@code saveScriptResult} — see: one chat's kept
 * results, or — for a run that belongs to no chat — the refusal that says so.
 *
 * <p>Every refusal is an {@link IllegalArgumentException}, which the runner reports as a RUNTIME
 * error with the message intact: the model's next move depends on which of "no chat", "no such id"
 * and "dropped as too old" it was, so the message names the ids that do exist.
 */
public final class ScriptResultReader {

    private final @Nullable ScriptResultStore store;
    private final @Nullable String conversationId;

    private ScriptResultReader(@Nullable ScriptResultStore store, @Nullable String conversationId) {
        this.store = store;
        this.conversationId = conversationId;
    }

    public static ScriptResultReader of(ScriptResultStore store, @Nullable ResultScope scope) {
        return scope == null
                ? new ScriptResultReader(null, null)
                : new ScriptResultReader(store, scope.conversationId());
    }

    /**
     * The one spelling of {@code id} — {@code "R3"} and {@code "3"} are {@code "r3"} — so a cache
     * keyed on it charges a result once, however the script wrote its name. An id that is not one
     * comes back as written, and is refused with the list when read.
     *
     * @throws IllegalArgumentException the script passed no id at all
     */
    static String canonical(@Nullable String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "kb.result needs a result id, e.g. kb.result('r1'); kb.results() lists them.");
        }
        OptionalInt seq = ChatScriptResults.seqOf(id);
        return seq.isPresent() ? ChatScriptResults.idOf(seq.getAsInt()) : id.strip();
    }

    /** The value kept under {@code id}, as JSON. */
    public String valueJson(String id) {
        if (store == null || conversationId == null) {
            throw noChat();
        }
        return store.valueJson(conversationId, id)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No kept result '"
                                                + id
                                                + "' in this chat. "
                                                + available(store.list(conversationId))));
    }

    List<StoredScriptResult> list() {
        if (store == null || conversationId == null) {
            throw noChat();
        }
        return store.list(conversationId);
    }

    private static String available(List<StoredScriptResult> kept) {
        if (kept.isEmpty()) {
            return "It keeps none yet: a result gets its id (resultId in the tool result) when a"
                    + " script finishes and returns a value.";
        }
        return "Kept: "
                + kept.stream().map(StoredScriptResult::id).collect(Collectors.joining(", "))
                + " — older ones are dropped as new ones arrive.";
    }

    private static IllegalArgumentException noChat() {
        return new IllegalArgumentException(
                "kb.result/kb.results read the results kept by this chat's earlier scripts, and"
                        + " this run belongs to no chat.");
    }
}
