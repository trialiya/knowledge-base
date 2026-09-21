package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptParam;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.chat.script.SavedScriptCatalog;
import io.github.trialiya.kb.service.chat.script.ScriptArgs;
import io.github.trialiya.kb.service.chat.script.ScriptRequest;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.chat.script.ScriptSource;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Settings panel's script bench: runs a script the <em>user</em> wrote, through the same {@link
 * ScriptRunner} the {@code runScript} tool uses, and returns the same {@link ScriptResult}. It
 * exists so that the sandbox and its budgets can be tried out — "what does kb.grep actually
 * return", "is 10s enough for a repository-wide pass" — without going through a chat turn and
 * hoping the model writes the script one meant.
 *
 * <p><b>Read-only, always.</b> The run is forced read-only ({@code forceReadOnly=true}), so {@code
 * kb.edit}/{@code kb.create} are not bound however {@code kb.script.edit-enabled} is set. A bench
 * for trying things out is the wrong place to acquire a working tree full of unreviewed changes:
 * edits belong to the chat, where the diff is shown and attributed to a message.
 *
 * <p><b>Gated by {@code kb.script.enabled}</b>, exactly like the tool. A deployment that turned
 * script execution off did so because executing submitted code is a denial-of-service surface (see
 * {@code ScriptRunner}), and that reasoning does not stop applying because the code arrives over
 * HTTP instead of from the model. Everything reachable from here is already reachable through the
 * existing read tools, so an authenticated user gains no access they did not have.
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/script")
public class ScriptTestController {

    private final ScriptRunner scriptRunner;
    private final ScriptProperties scriptProperties;

    /** The saved scripts a project declares — the second half of the bench (see {@link #saved}). */
    private final SavedScriptCatalog savedScripts;

    private final ProjectCatalog projects;

    public ScriptTestController(
            ScriptRunner scriptRunner,
            ScriptProperties scriptProperties,
            SavedScriptCatalog savedScripts,
            ProjectCatalog projects) {
        this.scriptRunner = scriptRunner;
        this.scriptProperties = scriptProperties;
        this.savedScripts = savedScripts;
        this.projects = projects;
    }

    /**
     * Runs one script and answers with its result — including a failed one, which is the whole
     * point of a bench: the error kind, the message and the line are what the user came for.
     */
    @PostMapping("/run")
    public ScriptResult run(@RequestBody ScriptRunRequest request) {
        requireEnabled();
        String script = request.script() == null ? "" : request.script();
        if (script.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script is empty");
        }
        log.info(
                "Settings script bench: {} chars, timeoutSeconds={}",
                script.length(),
                request.timeoutSeconds());
        // No RunCancellation: there is no chat run to stop, so the wall-clock budget is the only
        // limit — the same situation as the synchronous chat endpoint (see RunCancellation#none).
        return scriptRunner.run(script, request.timeoutSeconds(), RunCancellation.none(), true);
    }

    /**
     * What the repository declares right now, for the bench's picker: the same list the model gets
     * in its {@code <active-project>} block, read from the same working tree at the same moment.
     *
     * <p>Not gated on {@code kb.script.enabled} the way running is: with scripts off the list is
     * empty anyway (see {@code SavedScriptCatalog}), and an empty list is a better answer for a
     * panel than a 409 it would have to special-case.
     *
     * @param project which repository's manifest to read; omitted — the default project. The answer
     *     names the project that actually replied, because an id nobody configured resolves to the
     *     default one rather than failing
     */
    @GetMapping("/saved")
    public SavedScripts saved(@RequestParam(required = false) @Nullable String project) {
        Project resolved = projects.find(project).orElseGet(projects::defaultProject);
        List<SavedScriptView> scripts =
                savedScripts.scripts(resolved.id()).stream().map(SavedScriptView::of).toList();
        return new SavedScripts(resolved.id(), resolved.label(), scripts);
    }

    /**
     * Runs one of those scripts, by name and with arguments — the author's loop: edit the file, run
     * it, read the error with its line.
     *
     * <p>Read-only like everything else here, which also means a script the manifest marks as
     * writing is refused by name rather than run without its writes: half of such a script's work
     * silently not happening is worse than a refusal that says why.
     */
    @PostMapping("/run-saved")
    public ScriptResult runSaved(@RequestBody SavedScriptRunRequest request) {
        requireEnabled();
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Script name is empty");
        }
        SavedScript script = savedScripts.require(request.project(), name);
        ScriptArgs.Bound bound = ScriptArgs.bind(script, request.args());
        ScriptSource source = savedScripts.source(request.project(), script, bound.values(), true);
        log.info(
                "Settings script bench: saved script '{}' ({}), args={}",
                name,
                source.sourceName(),
                bound.values().keySet());
        return scriptRunner.run(
                new ScriptRequest(
                        source,
                        bound,
                        request.timeoutSeconds() == null
                                ? seconds(script)
                                : request.timeoutSeconds(),
                        true,
                        null,
                        request.project()),
                RunCancellation.none());
    }

    /** The script's own budget in the unit the runner takes; null leaves the configured default. */
    private static @Nullable Integer seconds(SavedScript script) {
        return script.timeout() == null
                ? null
                : (int) Math.max(1, Math.ceil(script.timeout().toMillis() / 1000.0));
    }

    private void requireEnabled() {
        if (!scriptProperties.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Scripts are disabled (kb.script.enabled=false)");
        }
    }

    /**
     * @param project id of the repository these scripts belong to
     * @param label its human-readable name, so the panel can say whose list this is
     * @param scripts what the manifest declares, in its own order; empty when it declares nothing,
     *     when the branch carries no manifest, or when scripts are switched off entirely
     */
    public record SavedScripts(String project, String label, List<SavedScriptView> scripts) {}

    /** One saved script as the panel shows it — everything but the code. */
    public record SavedScriptView(
            String name,
            String desc,
            String file,
            boolean write,
            @Nullable Long timeoutSeconds,
            List<ParamView> params) {

        static SavedScriptView of(SavedScript script) {
            return new SavedScriptView(
                    script.name(),
                    script.desc(),
                    script.file(),
                    script.write(),
                    script.timeout() == null ? null : script.timeout().toSeconds(),
                    script.params().stream().map(ParamView::of).toList());
        }
    }

    /** One declared argument, as a form field: name, hint, shape and what it falls back to. */
    public record ParamView(
            String name,
            String desc,
            String type,
            boolean required,
            @Nullable Object defaultValue) {

        static ParamView of(ScriptParam param) {
            return new ParamView(
                    param.name(),
                    param.desc(),
                    param.type().name().toLowerCase(java.util.Locale.ROOT),
                    param.required(),
                    param.defaultValue());
        }
    }

    /**
     * @param name the script's name in the project's manifest
     * @param args arguments by declared name; checked against the declaration before anything runs
     * @param project which repository's manifest the name comes from; null — the default project
     * @param timeoutSeconds wall-clock budget; null leaves the script's own, then the configured
     *     default
     */
    public record SavedScriptRunRequest(
            @Nullable String name,
            @Nullable Map<String, Object> args,
            @Nullable String project,
            @Nullable Integer timeoutSeconds) {}

    /**
     * @param script the script body, run as a function body — top-level {@code return} works
     * @param timeoutSeconds wall-clock budget; null for {@code kb.script.timeout}, clamped to
     *     {@code kb.script.max-timeout}
     */
    public record ScriptRunRequest(@Nullable String script, @Nullable Integer timeoutSeconds) {}
}
