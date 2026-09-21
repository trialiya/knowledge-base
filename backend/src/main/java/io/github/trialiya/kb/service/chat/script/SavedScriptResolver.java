package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.time.Duration;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Turns "run the script called X with these arguments" into a {@link ScriptRequest}, for all three
 * callers that say it: the model's {@code runSavedScript} tool, the settings bench, and the user's
 * {@code /script} command in chat.
 *
 * <p>One place, because the rules are the same for all three and none of them is allowed to have
 * its own version of them: which shelf the name comes off, what the declaration says about the
 * arguments, whose budget wins, and — the one that matters most — that an attachment runs read-only
 * however trusted the caller is. Three copies of that last line would eventually be two.
 */
@AllArgsConstructor
@Service
public class SavedScriptResolver {

    private final SavedScriptCatalog catalog;
    private final AttachmentScriptService attachmentScripts;

    /**
     * @param projectId the repository the run reads and writes; null — the chat's default project
     * @param name a name from the project's manifest, or {@code attachment:<id>}
     * @param args what the caller passed; checked against the declaration, if there is one
     * @param timeoutSeconds the caller's own budget; null leaves the script's, then the configured
     *     default
     * @param writesAllowed whether this caller may write at all — the answer of {@code
     *     ScriptEditPolicy} for the model and the chat command, and a plain {@code false} for the
     *     bench. Ignored for an attachment, which never writes
     * @param priorInvocations the chat response's tool history, when there is a response behind the
     *     call; null for the bench and for the user's own command
     * @throws IllegalArgumentException the name, the arguments or the file cannot be satisfied —
     *     every message is written to be shown to whoever asked
     */
    public ScriptRequest resolve(
            @Nullable String projectId,
            String name,
            @Nullable Map<String, Object> args,
            @Nullable Integer timeoutSeconds,
            boolean writesAllowed,
            @Nullable ToolInvocationCollector priorInvocations) {
        if (AttachmentScriptService.addresses(name)) {
            // Nothing declares an attachment's arguments, so they pass through as they came, and
            // nothing declares a budget either — the call's own is all there is. Writing takes a
            // flag of its own on top of the caller's own permission: the code came from whoever
            // uploaded the file (see AttachmentScriptService).
            ScriptArgs.Bound bound = ScriptArgs.free(name, args);
            boolean readOnly = !writesAllowed || !attachmentScripts.writesAllowed();
            return new ScriptRequest(
                    attachmentScripts.source(name, bound.values()),
                    bound,
                    timeoutSeconds,
                    readOnly,
                    priorInvocations,
                    projectId);
        }
        SavedScript script = catalog.require(projectId, name);
        boolean readOnly = !writesAllowed;
        // Arguments first, source second: a call that cannot be satisfied should not have cost a
        // read of the working tree, and the arguments are what a caller gets wrong.
        ScriptArgs.Bound bound = ScriptArgs.bind(script, args);
        return new ScriptRequest(
                catalog.source(projectId, script, bound.values(), readOnly),
                bound,
                timeout(timeoutSeconds, script),
                readOnly,
                priorInvocations,
                projectId);
    }

    /**
     * The call's own budget, then the script's, then the configured default. A script that declares
     * one does so because its normal pass does not fit in ten seconds; a caller that names one is
     * answering something it has just seen fail.
     */
    private static @Nullable Integer timeout(@Nullable Integer requested, SavedScript script) {
        if (requested != null && requested > 0) {
            return requested;
        }
        Duration declared = script.timeout();
        return declared == null ? null : (int) Math.max(1, Math.ceil(declared.toMillis() / 1000.0));
    }
}
