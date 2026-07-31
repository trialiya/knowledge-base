package io.github.trialiya.kb.service.script;

/**
 * The user stopped the chat response while a script was running.
 *
 * <p>Deliberately not a {@code ScriptResult} with an error: a cancelled run has no reader. The chat
 * stream it belonged to is already disposed, so returning a result would only feed a dead loop —
 * and, once step 2 lands, would be the one path where a buffered edit could still reach disk after
 * the user asked for the run to stop.
 */
public class ScriptCancelledException extends RuntimeException {

    public ScriptCancelledException(String message) {
        super(message);
    }
}
