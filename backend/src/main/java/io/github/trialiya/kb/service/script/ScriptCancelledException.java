package io.github.trialiya.kb.service.script;

/**
 * The user stopped the chat response while a script was running.
 *
 * <p>Deliberately not a {@code ScriptResult} with an error: a cancelled run has no reader. The chat
 * stream it belonged to is already disposed, so returning a result would only feed a dead loop —
 * and it is the one path where a buffered edit could still reach disk after the user asked for the
 * run to stop.
 *
 * <p><b>Throwing is not by itself enough</b>, which is why {@code ChatConfig#
 * toolExecutionExceptionProcessor} names this class. Spring AI treats a tool that throws as a tool
 * that failed, and by default hands the model the exception message as an ordinary tool result — so
 * without that registration this exception would arrive as a polite note that the run the user just
 * stopped may continue.
 */
public class ScriptCancelledException extends RuntimeException {

    public ScriptCancelledException(String message) {
        super(message);
    }
}
