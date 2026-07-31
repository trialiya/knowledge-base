package io.github.trialiya.kb.model.script;

import org.jspecify.annotations.Nullable;

/**
 * Why a script did not finish. Half the value of {@code runScript} is that the model can fix its
 * own script on the second attempt, which it can only do if the failure is specific: a syntax error
 * must carry the line, an exhausted budget must name <em>which</em> budget, and a timeout must be
 * distinguishable from both (the fixes differ — narrow the glob, split the run, correct the code).
 *
 * @param kind failure class; see {@link Kind}
 * @param message human-readable detail, taken verbatim from the engine or the budget check
 * @param line 1-based line in the script, when the engine reported one
 */
public record ScriptError(Kind kind, String message, @Nullable Integer line) {

    public enum Kind {
        /** The script did not parse. */
        SYNTAX,
        /** The script threw (or returned something unserialisable). */
        RUNTIME,
        /** Wall-clock budget exhausted. */
        TIMEOUT,
        /** A {@code kb.script.limits.*} budget was exceeded. */
        BUDGET,
        /** The user stopped the chat response while the script was running. */
        CANCELLED
    }

    public static ScriptError of(Kind kind, String message) {
        return new ScriptError(kind, message, null);
    }
}
