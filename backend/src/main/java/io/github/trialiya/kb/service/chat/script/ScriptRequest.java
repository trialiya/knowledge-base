package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.tools.ToolInvocationCollector;
import org.jspecify.annotations.Nullable;

/**
 * Everything one script run is, apart from the stop flag: where its text came from, what it was
 * called with, and under which permissions it runs.
 *
 * <p>A record rather than four more parameters on {@code ScriptRunner.run}: the two callers that
 * matter — an inline {@code runScript} and a saved script with arguments — differ in half of these
 * fields, and a seven-argument call site says nothing about which half.
 *
 * @param source the script and where it came from
 * @param args the arguments, already checked against the script's declaration ({@code ScriptArgs})
 * @param timeoutSeconds requested wall-clock budget; clamped to {@code kb.script.max-timeout}, null
 *     for the configured default
 * @param forceReadOnly withhold the write methods whatever {@code ScriptEditPolicy} says — the
 *     search sub-agent and the settings bench, which are read-only by construction
 * @param priorInvocations the chat response's tool history, so the read-before-overwrite rule also
 *     honours a file another tool already showed this response (see {@code ScriptSession}); null
 *     where there is no such session (background jobs, tests)
 * @param projectId the repository the run reads and writes; null — the default project
 * @param results the chat whose kept results the run reads and, if it says so, adds to; null for a
 *     run that belongs to no chat
 */
public record ScriptRequest(
        ScriptSource source,
        ScriptArgs.Bound args,
        @Nullable Integer timeoutSeconds,
        boolean forceReadOnly,
        @Nullable ToolInvocationCollector priorInvocations,
        @Nullable String projectId,
        @Nullable ResultScope results) {

    /** The same run, belonging to {@code results}'s chat. */
    public ScriptRequest withResults(ResultScope results) {
        return new ScriptRequest(
                source, args, timeoutSeconds, forceReadOnly, priorInvocations, projectId, results);
    }
}
