package io.github.trialiya.kb.model.git.dto;

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
 */
public record GitCommandResult(String command, String output, GitBranchStatus status) {}
