package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.controller.ScriptTestController.SavedScriptRunRequest;
import io.github.trialiya.kb.controller.ScriptTestController.ScriptRunRequest;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.script.AttachmentScriptService;
import io.github.trialiya.kb.service.chat.script.SavedScriptCatalog;
import io.github.trialiya.kb.service.chat.script.SavedScriptResolver;
import io.github.trialiya.kb.service.chat.script.ScheduledScriptService;
import io.github.trialiya.kb.service.chat.script.ScriptRequest;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.support.TestProjects;
import io.github.trialiya.kb.tools.RunCancellation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The script bench is the one place where code arrives from the browser instead of from the model,
 * so the two properties that keep it from widening anything — the {@code kb.script.enabled} gate
 * and the forced read-only run — are what is worth pinning down.
 */
class ScriptTestControllerTest {

    private static final String MANIFEST = ".kb/scripts.yaml";

    @TempDir Path repoDir;

    /** Вложения стенд не показывает, но резолвер общий — мок нужен, чтобы его собрать. */
    private final AttachmentService attachments = mock(AttachmentService.class);

    private static final ScriptResult EMPTY_RESULT =
            new ScriptResult(
                    "default",
                    null,
                    null,
                    null,
                    List.of(),
                    new ScriptStats(0, 0, 0, 0, 0),
                    null,
                    List.of(),
                    List.of());

    /**
     * Even the free-form runs need the repository: the controller opens it to build a catalogue.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repo();
    }

    private ScriptTestController controller(ScriptRunner runner, boolean enabled) {
        ScriptProperties properties = properties(enabled);
        ProjectOption option =
                new ProjectOption(
                        TestProjects.ID,
                        null,
                        repoDir.toString(),
                        true,
                        false,
                        null,
                        null,
                        MANIFEST,
                        null,
                        true);
        ProjectCatalog projects =
                new ProjectCatalog(new ProjectProperties(List.of(option)), new GitProperties(null));
        GitRegistry registry = TestProjects.registry(List.of(option));
        SavedScriptCatalog catalog = new SavedScriptCatalog(projects, registry, properties);
        return new ScriptTestController(
                runner,
                properties,
                catalog,
                new SavedScriptResolver(
                        catalog, new AttachmentScriptService(attachments, properties)),
                mock(ScheduledScriptService.class),
                projects);
    }

    private static ScriptProperties properties(boolean enabled) {
        return new ScriptProperties(
                enabled, true, true, false, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("with kb.script.enabled=false the bench refuses to run anything")
    void refusesWhenDisabled() {
        ScriptRunner runner = mock(ScriptRunner.class);
        ScriptTestController controller = controller(runner, false);

        assertThatThrownBy(() -> controller.run(new ScriptRunRequest("return 1;", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("an empty script is a bad request, not an empty run")
    void refusesEmptyScript() {
        ScriptRunner runner = mock(ScriptRunner.class);
        ScriptTestController controller = controller(runner, true);

        assertThatThrownBy(() -> controller.run(new ScriptRunRequest("  \n ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("the run is always forced read-only, whatever kb.script.edit-enabled says")
    void alwaysRunsReadOnly() {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.run(any(), any(), any(), eq(true))).thenReturn(EMPTY_RESULT);
        ScriptTestController controller = controller(runner, true);

        assertThat(controller.run(new ScriptRunRequest("return 1;", 5))).isSameAs(EMPTY_RESULT);
        verify(runner).run(eq("return 1;"), eq(5), any(RunCancellation.class), eq(true));
    }

    // ── Saved scripts ───────────────────────────────────────────────────────

    /**
     * The picker's list is the same one the model gets, read from the same working tree — and it is
     * <em>not</em> behind the enabled gate that running is: with scripts off the list is empty
     * anyway, and an empty list is a better answer for a panel than a 409 to special-case.
     */
    @Test
    @DisplayName("the bench lists what the repository declares right now")
    void listsSavedScripts() {
        ScriptTestController controller = controller(mock(ScriptRunner.class), true);

        ScriptTestController.SavedScripts saved = controller.saved(null);

        assertThat(saved.project()).isEqualTo(TestProjects.ID);
        assertThat(saved.scripts()).extracting(s -> s.name()).containsExactly("report", "bump");
        assertThat(saved.scripts().getFirst().params())
                .extracting(p -> p.name(), p -> p.type(), p -> p.required())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("area", "string", true));
    }

    /**
     * A saved script runs read-only here even where the project itself may be written to: the bench
     * is for trying things out, and a tree full of unreviewed changes is the opposite of that.
     */
    @Test
    @DisplayName("a saved script runs read-only, with its declared arguments checked first")
    void runsSavedScriptReadOnly() {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.run(any(ScriptRequest.class), any(RunCancellation.class)))
                .thenReturn(EMPTY_RESULT);
        ScriptTestController controller = controller(runner, true);

        assertThat(
                        controller.runSaved(
                                new SavedScriptRunRequest(
                                        "report", Map.of("area", "docs"), null, null)))
                .isSameAs(EMPTY_RESULT);

        org.mockito.ArgumentCaptor<ScriptRequest> request =
                org.mockito.ArgumentCaptor.forClass(ScriptRequest.class);
        verify(runner).run(request.capture(), any(RunCancellation.class));
        assertThat(request.getValue().forceReadOnly()).isTrue();
        assertThat(request.getValue().args().values()).containsEntry("area", "docs");
        assertThat(request.getValue().source().text()).contains("kb.files");
    }

    @Test
    @DisplayName("an unknown name, a missing required argument and a writing script are refused")
    void refusesWhatItCannotRun() {
        ScriptRunner runner = mock(ScriptRunner.class);
        ScriptTestController controller = controller(runner, true);

        // Each of these is the request's fault, so each is a 400 with the reason in it — not a 500
        // with a stack trace, which is what an unmapped IllegalArgumentException would have been.
        assertBadRequest(
                () -> controller.runSaved(new SavedScriptRunRequest("repoort", null, null, null)),
                "report");
        assertBadRequest(
                () -> controller.runSaved(new SavedScriptRunRequest("report", null, null, null)),
                "area");
        assertBadRequest(
                () ->
                        controller.runSaved(
                                new SavedScriptRunRequest(
                                        "report", Map.of("area", List.of("a")), null, null)),
                "must be a string");
        // Declared as writing: the bench never writes, so it says so instead of running the script
        // with half its job silently undone.
        assertBadRequest(
                () -> controller.runSaved(new SavedScriptRunRequest("bump", null, null, null)),
                "edits files");
        assertThatThrownBy(
                        () -> controller.runSaved(new SavedScriptRunRequest(" ", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("with scripts off, running a saved script is refused like any other run")
    void refusesSavedScriptWhenDisabled() {
        ScriptRunner runner = mock(ScriptRunner.class);

        assertThatThrownBy(
                        () ->
                                controller(runner, false)
                                        .runSaved(
                                                new SavedScriptRunRequest(
                                                        "report", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(runner);
    }

    private static void assertBadRequest(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String saying) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(saying)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    /** A repository with a manifest: the list and the run both read the working tree. */
    private void repo() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve("tools/report.js"), "return kb.files('**/*.md').length;\n");
        write(repoDir.resolve("tools/bump.js"), "return 1;\n");
        write(
                repoDir.resolve(MANIFEST),
                """
                scripts:
                  - name: report
                    file: tools/report.js
                    desc: Count the markdown files
                    params:
                      - { name: area, desc: 'A subtree', required: true }
                  - { name: bump, file: tools/bump.js, desc: Would edit files, write: true }
                """);
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void runGit(String... args) {
        try {
            var command = new ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }
}
