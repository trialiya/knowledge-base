package io.github.trialiya.kb.model.chat.entity;

import org.jspecify.annotations.Nullable;

/**
 * A git command the user ran from this chat, kept on the row that command left in the history (see
 * {@code ChatHistoryService.appendGitEvent}).
 *
 * <p>One record serves both readers, and they need different halves of it: the panel draws {@link
 * #output()} in full, the model gets a sentence built from {@link #command()}, {@link #ok()} and
 * {@link #branch()} (see {@code ChatHistoryService.gitCommandNotice}). Keeping the output here
 * rather than in the row's content is what lets the notice be assembled at read time — the same
 * arrangement the project-switch marker uses, and for the same reason: the wording sent to the
 * model is ours to change without rewriting history.
 *
 * @param command the command as it was run ({@code "pull"}, {@code "switch feature/x"}) — {@code
 *     GitCommandResult.command}, or the verb alone when the command never got far enough to have
 *     one
 * @param project canonical id of the repository it ran against; null only for a deployment with no
 *     project configured, where "which repository" has one answer
 * @param ok whether git accepted it. A refusal is kept, not dropped: it is the half the user most
 *     needs to see again, and the model reading "push was rejected" is the point of telling it at
 *     all
 * @param output git's own words — its output on success, its refusal on failure, both as they came.
 *     Empty when the command said nothing, which for several git commands is the ordinary success
 * @param branch the branch the working tree sat on after the command; null on a refusal, which
 *     carries git's message and no state — and needs none, since a refused command left the branch
 *     where the row above already says it was
 */
public record GitEventMeta(
        String command,
        @Nullable String project,
        boolean ok,
        String output,
        @Nullable String branch) {

    public GitEventMeta {
        output = output == null ? "" : output;
    }
}
