package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.StoredScriptResult;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What {@code kb.result} / {@code kb.results} see: one chat's kept results, or — for a run that
 * belongs to no chat — the refusal that says so.
 *
 * <p>Every refusal is an {@link IllegalArgumentException}, which the runner reports as a RUNTIME
 * error with the message intact: the model's next move depends on which of "no chat", "no such id"
 * and "dropped as too old" it was, so the message names the ids that do exist.
 */
final class ScriptResultReader {

    private final @Nullable ScriptResultStore store;
    private final @Nullable String conversationId;

    private ScriptResultReader(@Nullable ScriptResultStore store, @Nullable String conversationId) {
        this.store = store;
        this.conversationId = conversationId;
    }

    static ScriptResultReader of(ScriptResultStore store, @Nullable ResultScope scope) {
        return scope == null
                ? new ScriptResultReader(null, null)
                : new ScriptResultReader(store, scope.conversationId());
    }

    /** The value kept under {@code id}, as JSON. */
    String valueJson(String id) {
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
