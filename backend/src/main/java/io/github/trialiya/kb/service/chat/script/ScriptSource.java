package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.ScriptRunSource;
import org.jspecify.annotations.Nullable;

/**
 * The text of one run and where it came from — the single thing {@code ScriptRunner} needs in order
 * not to care whether the model wrote the script or picked it off the project's shelf.
 *
 * @param text the script body, run as a function body
 * @param sourceName what the engine calls this source, and therefore what a reported error line
 *     belongs to: the repository path for a saved script, {@code script.js} for an inline one
 * @param report what the result tells its readers about the source; null for an inline script,
 *     where the call's own argument is the text
 */
public record ScriptSource(String text, String sourceName, @Nullable ScriptRunSource report) {

    /** A script the model wrote in the call itself. */
    public static ScriptSource inline(String text) {
        return new ScriptSource(text, "script.js", null);
    }
}
