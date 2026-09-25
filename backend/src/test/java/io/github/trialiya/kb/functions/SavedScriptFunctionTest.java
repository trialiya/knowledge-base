package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.script.AttachmentScriptService;
import io.github.trialiya.kb.service.chat.script.InMemoryScriptResultStore;
import io.github.trialiya.kb.service.chat.script.SavedScriptCatalog;
import io.github.trialiya.kb.service.chat.script.SavedScriptResolver;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.support.TestProjects;
import io.github.trialiya.kb.tools.ProjectContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

/**
 * {@code runSavedScript} end to end: a name off the repository's manifest, arguments from the call,
 * and the sandbox that {@code runScript} already uses.
 *
 * <p>What is checked here and nowhere else is the seam between the three — that the arguments reach
 * the script as a real JavaScript object, that a failure's line belongs to the file the model never
 * saw, and that the result says which script ran.
 */
class SavedScriptFunctionTest {

    private static final String MANIFEST = ".kb/scripts.yaml";

    @TempDir Path repoDir;

    /**
     * Вложения этому тесту нужны одной веткой: их собственный разбор — в
     * AttachmentScriptServiceTest.
     */
    private final AttachmentService attachments = org.mockito.Mockito.mock(AttachmentService.class);

    private final ToolContext context =
            new ToolContext(Map.of(ProjectContext.KEY, TestProjects.ID));

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve("docs/readme.md"), "hello\n");
        write(
                repoDir.resolve("tools/report.js"),
                "kb.log('area=' + args.area);\nreturn { area: args.area, limit: args.limit };\n");
        write(repoDir.resolve("tools/boom.js"), "const a = 1;\nthrow new Error('boom');\n");
        write(repoDir.resolve("tools/broken.js"), "var a = 1;\nvar b = ;\nreturn a;\n");
        write(repoDir.resolve("tools/frozen.js"), "args.area = 'changed';\nreturn args.area;\n");
        write(
                repoDir.resolve("tools/echo.js"),
                "return { keys: Object.keys(args), area: args.area };\n");
        write(
                repoDir.resolve(MANIFEST),
                """
                scripts:
                  - name: report
                    file: tools/report.js
                    desc: Report on an area
                    params:
                      - { name: area, required: true }
                      - { name: limit, type: number, default: 5 }
                  - { name: boom, file: tools/boom.js, desc: Always throws }
                  - { name: broken, file: tools/broken.js, desc: Does not parse }
                  - { name: frozen, file: tools/frozen.js, desc: Tries to change its arguments }
                  - { name: echo, file: tools/echo.js, desc: Returns what it was given }
                  - { name: bump, file: tools/report.js, desc: Would edit files, write: true }
                """);
        commitAll();
    }

    @Test
    void runsTheNamedScriptWithItsArguments() {
        ScriptResult result =
                function(false).runSavedScript(context, "report", Map.of("area", "docs"), null);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(Map.of("area", "docs", "limit", 5));
        assertThat(result.log()).contains("area=docs");
    }

    /** The result is about a text the model never saw, so it has to say which one it was. */
    @Test
    void reportsWhichScriptRan() {
        ScriptResult result =
                function(false).runSavedScript(context, "report", Map.of("area", "docs"), null);

        ScriptRunSource source = result.source();
        assertThat(source).isNotNull();
        assertThat(source.kind()).isEqualTo(ScriptRunSource.Kind.PROJECT);
        assertThat(source.name()).isEqualTo("report");
        assertThat(source.path()).isEqualTo("tools/report.js");
        assertThat(source.sha()).isNotEmpty();
        // The arguments as the script saw them — the default filled in, not what the call passed.
        assertThat(source.args()).containsExactly(Map.entry("area", "docs"), Map.entry("limit", 5));
    }

    /**
     * The wrapper keeps the script's own first line first, so a reported line is a line of the file
     * — which is the only thing that makes it useful for a text the model did not write.
     */
    @Test
    void aReportedLineIsALineOfTheScriptFile() {
        ScriptResult result = function(false).runSavedScript(context, "broken", null, null);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.SYNTAX);
        assertThat(result.error().line()).isEqualTo(2);
        assertThat(result.source()).isNotNull();
        assertThat(result.source().path()).isEqualTo("tools/broken.js");
    }

    /** A failed run is a result, and it still says which script failed. */
    @Test
    void aFailedRunStillNamesItsScript() {
        ScriptResult result = function(false).runSavedScript(context, "boom", null, null);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("boom");
        assertThat(result.source()).isNotNull();
        assertThat(result.source().path()).isEqualTo("tools/boom.js");
    }

    @Test
    void argumentsCannotBeChangedByTheScript() {
        ScriptResult result =
                function(false).runSavedScript(context, "frozen", Map.of("area", "docs"), null);

        // Frozen, not refused: a write to a frozen object is silently ignored outside strict mode,
        // which is what a script body is.
        assertThat(result.value()).isEqualTo("docs");
    }

    /**
     * The guest parses the arguments itself, so nothing is re-encoded on the way in: a non-ASCII
     * value arrives as it was passed, and a key named {@code __proto__} stays an own property
     * instead of silently becoming the object's prototype — which an object literal in the source
     * would have made it.
     */
    @Test
    void argumentsArriveAsPassed() {
        ScriptResult result =
                function(false)
                        .runSavedScript(
                                context,
                                "echo",
                                Map.of("area", "документы/раздел", "__proto__", "harmless"),
                                null);

        assertThat(result.error()).isNull();
        assertThat(result.value())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("area", "документы/раздел")
                .extracting("keys")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("area", "__proto__");
    }

    @Test
    void refusesACallThatTheDeclarationCannotSatisfy() {
        SavedScriptFunction function = function(false);

        assertThatThrownBy(() -> function.runSavedScript(context, "report", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("area");
        assertThatThrownBy(() -> function.runSavedScript(context, "reportt", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("report");
        assertThatThrownBy(() -> function.runSavedScript(context, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A script the manifest marks as writing, where nothing may write, is refused before it runs —
     * failing halfway would leave some of its work done and none of it written.
     */
    @Test
    void refusesAWritingScriptWhereWritesAreUnavailable() {
        assertThatThrownBy(
                        () ->
                                function(false)
                                        .runSavedScript(
                                                context, "bump", Map.of("area", "docs"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("edits files");

        assertThat(
                        function(true)
                                .runSavedScript(context, "bump", Map.of("area", "docs"), null)
                                .error())
                .isNull();
    }

    // ── Attachments ─────────────────────────────────────────────────────────

    /**
     * The other shelf: a script that arrived as an attachment. It runs in the same sandbox with the
     * same budgets — and never with the write methods, even here, where the project itself accepts
     * writes: what is different about an attachment is not what it may do but who wrote it.
     */
    @Test
    void anAttachmentRunsAndRunsReadOnly() {
        stubAttachment(12, "probe.js", "return [typeof kb.create, args.area];");

        ScriptResult result =
                function(true)
                        .runSavedScript(context, "attachment:12", Map.of("area", "docs"), null);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(List.of("undefined", "docs"));
        assertThat(result.source()).isNotNull();
        assertThat(result.source().kind()).isEqualTo(ScriptRunSource.Kind.ATTACHMENT);
        assertThat(result.source().name()).isEqualTo("attachment:12");
        assertThat(result.source().path()).isEqualTo("probe.js");
        // Nothing declares an attachment's arguments, so nothing is noted about them either.
        assertThat(result.log()).isEmpty();
    }

    /**
     * Nothing declares an attachment's budget, so the call's own {@code timeoutSeconds} is the only
     * one there is — and a run that ignored it would leave the model looping on TIMEOUT with no way
     * to ask for more. The elapsed time is what the assertion is about: with the argument dropped
     * this would still time out, just ten seconds later.
     */
    @Test
    void anAttachmentRunsUnderTheCallsOwnTimeout() {
        stubAttachment(14, "spin.js", "while (true) {}");

        ScriptResult result = function(false).runSavedScript(context, "attachment:14", null, 1);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.TIMEOUT);
        assertThat(result.stats().elapsedMs()).isLessThan(5_000);
    }

    @Test
    void anAttachmentThatIsNotAScriptIsRefusedBeforeTheSandbox() {
        stubAttachment(13, "notes.md", "# not a script");

        assertThatThrownBy(
                        () -> function(false).runSavedScript(context, "attachment:13", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notes.md");
    }

    /**
     * Писать вложению можно только там, где это включили отдельно: кода из вложения это касается не
     * потому, что он слабее, а потому, что принёс его кто-то другой.
     */
    @Test
    void anAttachmentWritesOnlyWhereTheDeploymentAllowedIt() {
        stubAttachment(15, "probe.js", "return typeof kb.create;");

        assertThat(function(true).runSavedScript(context, "attachment:15", null, null).value())
                .isEqualTo("undefined");
        assertThat(
                        function(true, true)
                                .runSavedScript(context, "attachment:15", null, null)
                                .value())
                .isEqualTo("function");
    }

    private void stubAttachment(long id, String fileName, String content) {
        org.mockito.Mockito.when(attachments.getById(id))
                .thenReturn(
                        new io.github.trialiya.kb.model.attachment.dto.Attachment(
                                id,
                                io.github.trialiya.kb.model.attachment.entity.AttachmentOwnerType
                                        .CHAT,
                                null,
                                "conv-1",
                                fileName,
                                "text/plain",
                                content.length(),
                                null,
                                null,
                                java.time.OffsetDateTime.now(),
                                java.time.OffsetDateTime.now()));
        org.mockito.Mockito.when(attachments.getContent(id)).thenReturn(content);
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private SavedScriptFunction function(boolean editEnabled) {
        return function(editEnabled, false);
    }

    private SavedScriptFunction function(boolean editEnabled, boolean attachmentEdit) {
        ProjectOption option =
                new ProjectOption(
                        TestProjects.ID,
                        null,
                        repoDir.toString(),
                        editEnabled,
                        false,
                        null,
                        null,
                        MANIFEST,
                        null,
                        true);
        ProjectCatalog projects =
                new ProjectCatalog(new ProjectProperties(List.of(option)), new GitProperties(null));
        GitRegistry registry = TestProjects.registry(List.of(option));
        ScriptProperties properties =
                new ScriptProperties(
                        true,
                        true,
                        true,
                        attachmentEdit,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        ScriptEditPolicy editPolicy = new ScriptEditPolicy(registry, properties);
        return new SavedScriptFunction(
                new SavedScriptResolver(
                        new SavedScriptCatalog(projects, registry, properties),
                        new AttachmentScriptService(attachments, properties)),
                new ScriptRunner(
                        registry, null, properties, editPolicy, new InMemoryScriptResultStore()),
                editPolicy);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll() {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
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
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }
}
