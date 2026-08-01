package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.script.ScriptRunner;
import io.github.trialiya.kb.tools.RunCancellation;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ScriptTestController(ScriptRunner scriptRunner, ScriptProperties scriptProperties) {
        this.scriptRunner = scriptRunner;
        this.scriptProperties = scriptProperties;
    }

    /**
     * Runs one script and answers with its result — including a failed one, which is the whole
     * point of a bench: the error kind, the message and the line are what the user came for.
     */
    @PostMapping("/run")
    public ScriptResult run(@RequestBody ScriptRunRequest request) {
        if (!scriptProperties.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Scripts are disabled (kb.script.enabled=false)");
        }
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
     * @param script the script body, run as a function body — top-level {@code return} works
     * @param timeoutSeconds wall-clock budget; null for {@code kb.script.timeout}, clamped to
     *     {@code kb.script.max-timeout}
     */
    public record ScriptRunRequest(@Nullable String script, @Nullable Integer timeoutSeconds) {}
}
