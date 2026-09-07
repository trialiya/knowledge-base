package io.github.trialiya.kb.service.file.git;

/**
 * A content search did not finish within its deadline and was cut short.
 *
 * <p>Its own type because the caller's answer differs from the other ways {@code git grep} can
 * fail: nothing is wrong with the request or the server, this one walk was too long — {@code 503}
 * on the search page, a retry with a narrower pathspec for the model. Still an {@link
 * IllegalStateException}, so callers that only tell "bad request" from "failure" keep working.
 */
public class GitGrepTimeoutException extends IllegalStateException {

    public GitGrepTimeoutException(String message) {
        super(message);
    }
}
