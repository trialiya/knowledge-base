package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * What one git command a user ran did — its own output, and the branch state left behind.
 *
 * <p>The state travels with the result on purpose: every one of these commands exists to change it,
 * and a client that had to ask for it separately would draw one frame of the state before the
 * command it just ran.
 *
 * @param command the command as it was run, without arguments a caller did not choose ({@code
 *     "fetch"}) — what the UI shows above the output and, later, what the chat tells the model
 * @param output the command's own output, trimmed and capped; empty when it said nothing, which for
 *     several git commands is the ordinary success
 * @param status the branch state after the command
 * @param commit full hash of the commit the command created — only {@code commit} creates one; the
 *     chat row links to it, and the model is told which commit to read
 */
public record GitCommandResult(
        String command,
        String output,
        GitBranchStatus status,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String commit) {

    /** A command that created no commit. */
    public GitCommandResult(String command, String output, GitBranchStatus status) {
        this(command, output, status, null);
    }

    /** The same result, naming the commit the command created. */
    public GitCommandResult withCommit(@Nullable String hash) {
        return new GitCommandResult(command, output, status, hash);
    }
}
