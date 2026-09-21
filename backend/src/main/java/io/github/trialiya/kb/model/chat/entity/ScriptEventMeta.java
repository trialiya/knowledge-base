package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.model.script.ScriptStats;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A saved script the user ran from this chat themselves (the {@code /script} command), kept on the
 * row that run left in the history — the third member of the family {@link GitEventMeta} and {@link
 * FileRevertMeta} belong to, and built the same way: the row carries what happened, the sentence
 * for the model is assembled at read time ({@code PromptNotices.scriptRunNotice}), so its wording
 * can change without rewriting history.
 *
 * <p>The whole {@code ScriptResult} is not kept. What the plaque shows and what the model needs are
 * the value, the failure and which files moved; the diffs are not here for the same reason the file
 * revert cannot undo a script's edits — they live in the working tree and in git, not in the chat.
 *
 * @param script the name it was run by — a manifest name, or {@code attachment:<id>}
 * @param path the file behind that name, as the result reported it; null when the run never got far
 *     enough to have one (an unknown name is refused before anything is read)
 * @param project canonical id of the repository it ran against
 * @param ok whether the script finished; a failed run is kept, and it is the half the user comes
 *     back to
 * @param value what the script returned, as it was returned — already bounded by {@code
 *     kb.script.limits.max-result-chars}
 * @param error why it stopped, or null
 * @param output everything the script wrote with {@code kb.log}, joined — bounded by {@code
 *     max-log-chars} the same way
 * @param edited paths the run created or modified; empty for a read-only run and for a failed one,
 *     which writes nothing at all
 * @param stats what the run spent — the same counters the tool's own result carries
 */
public record ScriptEventMeta(
        String script,
        @Nullable String path,
        @Nullable String project,
        boolean ok,
        @Nullable Object value,
        @Nullable ScriptError error,
        String output,
        List<String> edited,
        ScriptStats stats) {

    public ScriptEventMeta {
        output = output == null ? "" : output;
        edited = List.copyOf(edited == null ? List.of() : edited);
    }

    /** The row's shape, taken from the run that produced it. */
    public static ScriptEventMeta of(String script, ScriptResult result) {
        ScriptRunSource source = result.source();
        return new ScriptEventMeta(
                script,
                source == null ? null : source.path(),
                result.project(),
                result.error() == null,
                result.value(),
                result.error(),
                String.join("\n", result.log()),
                result.edits().stream().map(GitEditResult::path).toList(),
                result.stats());
    }
}
