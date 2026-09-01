package io.github.trialiya.kb.service.file.git;

/**
 * Another git command already holds this project's repository.
 *
 * <p>Its own type rather than a message on a generic failure because the caller's answer differs:
 * nothing is wrong with the request, and repeating it in a moment is the fix. Commands do not queue
 * — see {@code GitCommands}.
 */
public class GitBusyException extends RuntimeException {

    public GitBusyException(String message) {
        super(message);
    }

    public GitBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
