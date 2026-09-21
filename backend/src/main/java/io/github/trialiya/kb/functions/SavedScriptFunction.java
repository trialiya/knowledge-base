package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireText;

import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.chat.script.AttachmentScriptService;
import io.github.trialiya.kb.service.chat.script.SavedScriptCatalog;
import io.github.trialiya.kb.service.chat.script.ScriptArgs;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptRequest;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.chat.script.ScriptSource;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ProjectContext;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.time.Duration;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The {@code runSavedScript} tool: runs a script the active repository declared in its manifest, by
 * name and with arguments, instead of having the model write the same script again.
 *
 * <p>A tool of its own rather than another argument on {@code runScript}, because the call is a
 * different act: here the model <em>chooses</em> from a declared list instead of authoring code, so
 * the name is required and there is no script body at all. Two mutually exclusive arguments on one
 * tool is the shape weak models fill in wrongly — see "Незаполненные аргументы" in {@code
 * docs/проект/ai-инструменты.md}.
 *
 * <p>The catalogue itself is not in this description: it belongs to the active project, changes
 * with it, and is rendered into the {@code <active-project>} block by {@code SavedScriptCatalog} —
 * putting it here would rewrite the cached system prompt on every project switch.
 *
 * <p>Registered only when scripts are on and some project configured a manifest (see {@code
 * ChatConfig#savedScriptFunction}).
 */
@Slf4j
@AllArgsConstructor
public class SavedScriptFunction {

    private final SavedScriptCatalog catalog;

    /** The other shelf: {@code attachment:<id>}, always read-only (see its own javadoc). */
    private final AttachmentScriptService attachmentScripts;

    private final ScriptRunner scriptRunner;

    /** Asked before the run, so a script declared to write is refused where nothing can. */
    private final ScriptEditPolicy editPolicy;

    @Tool(
            description =
                    """
                    Runs a script somebody already wrote, with arguments — use it instead of writing the \
                    same script yourself. Two things it runs: a script this repository saved under a name \
                    (the names, what each does and which arguments it takes are listed in the \
                    <active-project> block; no other name runs), and a JavaScript attachment, named \
                    "attachment:<id>" with the id from getChatAttachments / getDocumentAttachments — an \
                    attachment always runs read-only. Same sandbox, budgets and result shape as runScript. \
                    Returns: value (script result), log, stats, filesRead, edits, error \
                    (kind=SYNTAX|RUNTIME|TIMEOUT|BUDGET), and source (which script ran, its path and the \
                    arguments it got).
                    """,
            resultConverter = CompactToolResultConverter.class)
    public ScriptResult runSavedScript(
            ToolContext context,
            @ToolParam(
                            description =
                                    "Script name from the <active-project> list, or "
                                            + "\"attachment:<id>\" for a JavaScript attachment.")
                    String name,
            @ToolParam(
                            description =
                                    "Arguments as an object, e.g. {\"area\": \"frontend/src\"}. "
                                            + "Omit for a script that declares none.",
                            required = false)
                    @Nullable Map<String, Object> args,
            @ToolParam(
                            description =
                                    "Time limit in seconds. Omit to use the script's own budget "
                                            + "(default 10, max 30).",
                            required = false)
                    @Nullable Integer timeoutSeconds) {
        final String scriptName = requireText(name, "name");
        final String projectId = ProjectContext.from(context);
        final Run run =
                AttachmentScriptService.addresses(scriptName)
                        ? attachment(scriptName, args)
                        : saved(projectId, scriptName, args, timeoutSeconds);
        log.info(
                "runSavedScript called: '{}' ({}), args={}, project='{}', readOnly={}",
                scriptName,
                run.source().sourceName(),
                run.args().values().keySet(),
                projectId,
                run.readOnly());
        ScriptResult result =
                scriptRunner.run(
                        new ScriptRequest(
                                run.source(),
                                run.args(),
                                run.timeoutSeconds(),
                                run.readOnly(),
                                ToolInvocationCollector.from(context),
                                projectId),
                        RunCancellation.from(context));
        log.info("runSavedScript finished: {}", result.getFormattedResponse());
        return result;
    }

    /** One resolved call, before the runner is handed anything. */
    private record Run(
            ScriptSource source,
            ScriptArgs.Bound args,
            @Nullable Integer timeoutSeconds,
            boolean readOnly) {}

    /**
     * A script off the project's manifest. Arguments first, source second: a call that cannot be
     * satisfied should not have cost a read of the working tree, and the arguments are what the
     * model gets wrong.
     */
    private Run saved(
            @Nullable String projectId,
            String name,
            @Nullable Map<String, Object> args,
            @Nullable Integer timeoutSeconds) {
        final SavedScript script = catalog.require(projectId, name);
        final boolean readOnly = !editPolicy.enabled(projectId);
        final ScriptArgs.Bound bound = ScriptArgs.bind(script, args);
        return new Run(
                catalog.source(projectId, script, bound.values(), readOnly),
                bound,
                timeout(timeoutSeconds, script),
                readOnly);
    }

    /**
     * An attachment. Nothing declares its arguments, so they pass through as they came; nothing
     * declares its budget either, so the call's own {@code timeoutSeconds} is all there is; and the
     * run is read-only whatever the project allows — see {@code AttachmentScriptService}.
     */
    private Run attachment(String name, @Nullable Map<String, Object> args) {
        final ScriptArgs.Bound bound = ScriptArgs.free(name, args);
        return new Run(attachmentScripts.source(name, bound.values()), bound, null, true);
    }

    /**
     * The call's own budget, then the script's, then the configured default. A script that declares
     * one does so because its normal pass does not fit in ten seconds; a model that names one is
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
