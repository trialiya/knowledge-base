package io.github.trialiya.kb.model.script;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * A script result a chat kept, as a listing describes it — everything but the value itself, which
 * is read on its own ({@code ScriptResultStore#valueJson}) because it can be a megabyte.
 *
 * @param id what the model and the user call it — {@code r3}
 * @param script the name the script was run by; null for a script the model wrote inline
 * @param project canonical id of the repository the run read
 * @param chars length of the kept value as JSON
 * @param createdAt when the run that produced it finished
 */
public record StoredScriptResult(
        String id,
        @Nullable String script,
        @Nullable String project,
        int chars,
        LocalDateTime createdAt) {}
