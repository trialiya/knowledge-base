package io.github.trialiya.kb.service.chat.script;

/**
 * The chat a run belongs to, as far as kept results go: whose earlier results {@code kb.result} may
 * read, and whether this run's own value is kept there too.
 *
 * <p>A run with no scope at all — the settings bench, a schedule — belongs to no chat, so it has
 * nothing to read and nowhere to keep.
 *
 * @param conversationId the chat
 * @param keep keep this run's value as the chat's next result. Off for the search sub-agent: the
 *     chat model never sees the sub-agent's scripts, so an id handed out there would be one nobody
 *     can name
 */
public record ResultScope(String conversationId, boolean keep) {

    /** Reads the chat's results and keeps this run's own. */
    public static ResultScope keeping(String conversationId) {
        return new ResultScope(conversationId, true);
    }

    /** Reads the chat's results, keeps nothing. */
    public static ResultScope readOnly(String conversationId) {
        return new ResultScope(conversationId, false);
    }
}
