package io.github.trialiya.kb.service.chat.script;

/**
 * A {@code kb.script.limits.*} budget was exhausted. Thrown from inside {@code KbScriptApi}, so it
 * surfaces to the guest as a normal exception — a script may legitimately catch it and return
 * partial results — and is recognised by {@code ScriptRunner} when it propagates out.
 *
 * <p>The message always names the budget that ran out: it is what the model reads to decide whether
 * to narrow a glob, read a line range instead of a whole file, or split the work in two calls.
 */
public class ScriptLimitExceededException extends RuntimeException {

    public ScriptLimitExceededException(String message) {
        super(message);
    }
}
