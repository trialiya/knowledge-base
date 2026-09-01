package io.github.trialiya.kb.service.file.git;

/**
 * Git ran and refused.
 *
 * <p>The message is git's own output, passed through unchanged: "Permission denied (publickey)" or
 * "couldn't find remote ref" is what tells the user what to fix, and any rewording would only lose
 * that. Distinct from an {@code IllegalStateException}, which here means the command could not be
 * run at all.
 */
public class GitCommandFailedException extends RuntimeException {

    public GitCommandFailedException(String message) {
        super(message);
    }

    public GitCommandFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
