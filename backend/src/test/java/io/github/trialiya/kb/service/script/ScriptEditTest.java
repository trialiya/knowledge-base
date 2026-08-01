package io.github.trialiya.kb.service.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.OutlineService;
import io.github.trialiya.kb.tools.RunCancellation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * Writing from a script. The property that matters most is all-or-nothing: {@code kb.edit} never
 * touches disk, so a script that fails, times out, blows a budget or is stopped by the user leaves
 * the working tree exactly as it found it. Everything else here — read-before-edit, the exact-match
 * contract, the write budgets — exists so that a model cannot rewrite files it has not looked at.
 */
class ScriptEditTest {

    private static final String APP_JAVA = "src/App.java";
    private static final String ORIGINAL = "class App {\n  void run() {}\n}\n";

    @TempDir Path repoDir;

    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve(APP_JAVA), ORIGINAL);
        write(repoDir.resolve("docs/readme.md"), "hello\n");
        commitAll();
        runner = newRunner(true, ScriptProperties.enabledWithDefaults());
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    void appliesAnEditAndReportsItWithADiff() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'void run() {}', 'void run() { start(); }');
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo("ok");
        assertThat(fileText(APP_JAVA)).contains("void run() { start(); }");
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path, GitEditResult::operation)
                .containsExactly(APP_JAVA, "edit");
        assertThat(result.edits().getFirst().diff()).contains("start();");
        assertThat(result.stats().filesEdited()).isEqualTo(1);
    }

    @Test
    void createsNewFilesAndStagesThemAsTracked() {
        ScriptResult result = run("kb.create('src/New.java', 'class New {}\\n'); return 'ok';");

        assertThat(result.error()).isNull();
        assertThat(fileText("src/New.java")).isEqualTo("class New {}\n");
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path)
                .isEqualTo("src/New.java");
    }

    @Test
    void aSecondEditOfTheSameFileSeesTheFirst() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class Application');
                        kb.edit('src/App.java', 'class Application', 'final class Application');
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileText(APP_JAVA)).startsWith("final class Application");
        // Two edits, one file — so one write and one diff, not two.
        assertThat(result.edits()).hasSize(1);
    }

    // ── All or nothing ──────────────────────────────────────────────────────

    @Test
    void writesNothingWhenTheScriptThrowsAfterEditing() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class Broken');
                        throw new Error('boom');
                        """);

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
        assertThat(result.edits()).isEmpty();
    }

    @Test
    void writesNothingWhenTheScriptTimesOut() {
        runner =
                newRunner(
                        true,
                        new ScriptProperties(
                                true,
                                true,
                                true,
                                null,
                                null,
                                null,
                                null,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                null,
                                null,
                                null));

        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class Spun');
                        while (true) {}
                        """);

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.TIMEOUT);
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    @Test
    void writesNothingWhenTheUserStopsTheRun() {
        RunCancellation cancellation = new RunCancellation(new AtomicBoolean(true));

        try {
            runner.run(
                    """
                    kb.read('src/App.java');
                    kb.edit('src/App.java', 'class App', 'class Stopped');
                    while (true) {}
                    """,
                    null,
                    cancellation);
        } catch (ScriptCancelledException expected) {
            // The point of the test is what is on disk, not the exception.
        }

        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    @Test
    void writesNothingWhenAWriteBudgetIsExceeded() {
        runner = newRunner(true, withEditLimits(1));

        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class Edited');
                        kb.create('src/Second.java', 'class Second {}');
                        return 'ok';
                        """);

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxEditedFiles");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
        assertThat(repoDir.resolve("src/Second.java")).doesNotExist();
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    @Test
    void refusesToEditAFileTheScriptHasNotRead() {
        ScriptResult result =
                run("kb.edit('src/App.java', 'class App', 'class Blind'); return 'ok';");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    /**
     * A grep match is current text of the file, which is all the rule ever asked for. Demanding a
     * whole read on top made the commonest edit — find a symbol, replace it everywhere — pay for
     * every file twice, while granting the script strictly more than the matched line it used.
     */
    @Test
    void acceptsAGrepMatchAsHavingSeenTheFile() {
        ScriptResult result =
                run(
                        """
                        var hits = kb.grep('class App', { glob: '**/*.java' });
                        kb.edit(hits[0].path, 'class App', 'class Grepped');
                        return hits.length;
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileText(APP_JAVA)).startsWith("class Grepped");
        // The file was never read, so it is not reported as read — evidence is not consumption.
        assertThat(result.filesRead()).isEmpty();
        assertThat(result.stats().filesRead()).isZero();
    }

    /** A grep that matched elsewhere says nothing about this file. */
    @Test
    void aGrepMatchInOneFileDoesNotUnlockAnother() {
        ScriptResult result =
                run(
                        """
                        kb.grep('hello', { glob: '**/*.md' });
                        kb.edit('src/App.java', 'class App', 'class Sneaky');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    @Test
    void acceptsARangeReadAsHavingSeenTheFile() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java', 1, 1);
                        kb.edit('src/App.java', 'class App', 'class Ranged');
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileText(APP_JAVA)).startsWith("class Ranged");
    }

    @Test
    void allowsEditingAFileTheSameScriptJustCreated() {
        // The read-before-edit rule exists so a model edits real content; content the script wrote
        // itself moments ago already satisfies that.
        ScriptResult result =
                run(
                        """
                        kb.create('src/Fresh.java', 'class Fresh {}\\n');
                        kb.edit('src/Fresh.java', 'class Fresh', 'final class Fresh');
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileText("src/Fresh.java")).isEqualTo("final class Fresh {}\n");
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::operation)
                .isEqualTo("create");
    }

    @Test
    void refusesAnAmbiguousOldStringUnlessReplaceAllIsAsked() {
        write(repoDir.resolve("src/Twice.java"), "int a = 1;\nint a = 1;\n");
        commitAll();

        ScriptResult ambiguous =
                run(
                        """
                        kb.read('src/Twice.java');
                        kb.edit('src/Twice.java', 'int a = 1;', 'int a = 2;');
                        return 'ok';
                        """);
        assertThat(ambiguous.error()).isNotNull();
        assertThat(ambiguous.error().message()).contains("occurs 2 times");
        assertThat(fileText("src/Twice.java")).isEqualTo("int a = 1;\nint a = 1;\n");

        ScriptResult all =
                run(
                        """
                        kb.read('src/Twice.java');
                        kb.edit('src/Twice.java', 'int a = 1;', 'int a = 2;', true);
                        return 'ok';
                        """);
        assertThat(all.error()).isNull();
        assertThat(fileText("src/Twice.java")).isEqualTo("int a = 2;\nint a = 2;\n");
    }

    @Test
    void refusesToCreateOverAnExistingFile() {
        ScriptResult result = run("kb.create('src/App.java', 'oops'); return 'ok';");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("already exists");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    /**
     * A path {@code createFile} could never write is refused while the script is still running, not
     * when the run's writes are applied. Left to the apply step it was the one way to break
     * all-or-nothing without the tree changing underneath: the files staged before it were already
     * on disk by the time the bad one failed, and the script had no way to know.
     */
    @Test
    void refusesAnUnwritablePathBeforeAnythingIsStaged() {
        ScriptResult result =
                run(
                        """
                        kb.create('src/First.java', 'class First {}\\n');
                        kb.create('.git/hooks/pre-commit', 'rm -rf /');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains(".git");
        // The point of the test: the file staged before the bad one never reached disk either.
        assertThat(repoDir.resolve("src/First.java")).doesNotExist();
        assertThat(repoDir.resolve(".git/hooks/pre-commit")).doesNotExist();
        assertThat(result.edits()).isEmpty();
    }

    @Test
    void refusesAJunkFileNameBeforeAnythingIsStaged() {
        ScriptResult result =
                run(
                        """
                        kb.create('src/First.java', 'class First {}\\n');
                        kb.create('src/.DS_Store', 'junk');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(repoDir.resolve("src/First.java")).doesNotExist();
        assertThat(result.edits()).isEmpty();
    }

    @Test
    void refusesToEditAFileHiddenByTheGlobPolicy() {
        ScriptProperties hidden =
                new ScriptProperties(
                        true,
                        true,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("src/**"),
                        null);
        runner = newRunner(true, hidden);

        ScriptResult result =
                run("kb.edit('src/App.java', 'class App', 'class Sneaky'); return 'ok';");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("File not found");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    // ── One spelling per path ───────────────────────────────────────────────

    /**
     * A leading {@code ./} is a natural thing for a model to write, and it used to sink the whole
     * tool call: the file reached disk, {@code git add ./x} matched nothing, and the run died with
     * "edits partially applied — ignored by .gitignore" about a rule that does not exist.
     */
    @Test
    void createsAFileWhoseSpellingIsNotCanonical() {
        ScriptResult result = run("kb.create('./src//Fresh.java', 'class Fresh {}\\n'); return 1;");

        assertThat(result.error()).isNull();
        assertThat(fileText("src/Fresh.java")).isEqualTo("class Fresh {}\n");
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path)
                .isEqualTo("src/Fresh.java");
    }

    /**
     * Two spellings of one file are one file. Keyed on the raw string, the second edit was computed
     * from the copy on disk and staged separately — quietly discarding the first.
     */
    @Test
    void twoSpellingsOfOnePathAreOneStagedWrite() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class AppOne');
                        kb.edit('./src/App.java', 'void run', 'void start');
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(result.edits()).hasSize(1);
        assertThat(result.stats().filesEdited()).isEqualTo(1);
        // Both edits survived — the second saw the first, rather than the original on disk.
        assertThat(fileText(APP_JAVA)).contains("class AppOne").contains("void start");
    }

    /**
     * The glob policy is checked on the path, so it is only as strong as the spelling reaching it.
     * {@code secrets/**} does not match {@code ./secrets/x.pem}: before the paths were canonical
     * this slipped past the check and was stopped only by JGit declining to stage it — after the
     * content had already been written to disk once.
     */
    @Test
    void aDeniedPathCannotBeReachedByRespellingIt() {
        runner = newRunner(true, withDenyGlobs(List.of("secrets/**")));

        ScriptResult result = run("kb.create('./secrets/leak.pem', 'PRIVATE KEY'); return 1;");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("File not found");
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(repoDir.resolve("secrets/leak.pem")).doesNotExist();
    }

    // ── Where writes are not available at all ───────────────────────────────

    @Test
    void doesNotBindTheWriteMethodsWhenEditingIsDisabled() {
        runner = newRunner(false, ScriptProperties.enabledWithDefaults());

        assertThat(run("return typeof kb.edit;").value()).isEqualTo("undefined");
        assertThat(run("return typeof kb.create;").value()).isEqualTo("undefined");
        // Reading still works — only the writes are gone.
        assertThat(run("return kb.read('src/App.java').length > 0;").value()).isEqualTo(true);
    }

    @Test
    void withholdsWritesFromAForcedReadOnlyRunEvenWhereThePolicyAllowsThem() {
        // The search sub-agent's copy of the tool: read-only by construction, whatever the main
        // chat is allowed to do.
        ScriptResult result =
                runner.run("return typeof kb.edit;", null, RunCancellation.none(), true);

        assertThat(result.value()).isEqualTo("undefined");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ScriptResult run(String script) {
        return runner.run(script, null, RunCancellation.none());
    }

    private ScriptRunner newRunner(boolean editEnabled, ScriptProperties properties) {
        GitProperties gitProperties = new GitProperties(repoDir.toString(), editEnabled);
        GitService gitService = new GitService(gitProperties, new OutlineService());
        return new ScriptRunner(
                gitService,
                null,
                properties,
                new ScriptEditPolicy(gitProperties, properties, gitService));
    }

    private static ScriptProperties withDenyGlobs(List<String> deny) {
        return new ScriptProperties(
                true, true, true, null, null, null, null, null, null, null, null, deny, null);
    }

    private static ScriptProperties withEditLimits(int maxEditedFiles) {
        return new ScriptProperties(
                true,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ScriptProperties.Limits(
                        2000,
                        DataSize.ofMegabytes(32),
                        2000,
                        20_000,
                        20_000,
                        maxEditedFiles,
                        DataSize.ofKilobytes(256)),
                null,
                null);
    }

    private String fileText(String relativePath) {
        try {
            return Files.readString(repoDir.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
