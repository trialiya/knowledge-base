package io.github.trialiya.kb.service.file.git;

/**
 * A read that runs {@code git} as a subprocess ({@code git grep}, {@code git blame}) did not
 * finish within its deadline and was cut short.
 *
 * <p>Its own type because the caller's answer differs from the other ways the command can fail:
 * nothing is wrong with the request or the server, this one walk was too long — {@code 503} on the
 * page that asked, a retry with a narrower pathspec for the model. Still an {@link
 * IllegalStateException}, so callers that only tell "bad request" from "failure" keep working.
 */
public class GitReadTimeoutException extends IllegalStateException {

    public GitReadTimeoutException(String message) {
        super(message);
    }
}
