package io.github.trialiya.kb.tools;

import org.jspecify.annotations.Nullable;

/**
 * Where a tool answer too large for the model's context is kept whole, under an id the model can
 * hand to a script ({@code kb.result}) or to {@code saveScriptResult}.
 *
 * <p>A narrow interface of this package rather than the script-result store itself: {@code tools}
 * depends on nothing in {@code service}, and the one implementation is wired over that store in
 * {@code ChatConfig}.
 */
@FunctionalInterface
public interface ToolResultShelf {

    /**
     * Keeps {@code json} as the chat's next result. Never throws: the tool call has already
     * succeeded, and failing to keep its answer must not turn it into an error.
     *
     * @param label what produced the value, as a listing of kept results names it
     */
    Shelved keep(String conversationId, String label, String json);

    /**
     * @param id what the value can be read back by; null when it was not kept
     * @param note why not, when that is something the model can act on (the value was too large);
     *     null otherwise
     */
    record Shelved(@Nullable String id, @Nullable String note) {}
}
