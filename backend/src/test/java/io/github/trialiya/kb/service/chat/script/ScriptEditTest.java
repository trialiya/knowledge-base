package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.file.GitRegistry;
import io.github.trialiya.kb.support.TestProjects;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** A PNG header followed by NUL bytes — sniffs binary exactly as git's own heuristic does. */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    @TempDir Path repoDir;

    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve(APP_JAVA), ORIGINAL);
        write(repoDir.resolve("docs/readme.md"), "hello\n");
        writeBytes(repoDir.resolve("static/logo.png"), PNG);
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
                                null,
                                null,
                                null,
                                null,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
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
     * The read-before-edit rule is about the model, not about this one script: if {@code
     * getFileContent} already showed it the file earlier in the same chat-response, a script that
     * edits the same file without reading it again is not blind — it is reusing what the model
     * already saw through another tool.
     */
    @Test
    void acceptsAFileReadByAnotherToolEarlierInTheSameResponse() {
        ToolInvocationCollector collector = new ToolInvocationCollector();
        collector.record(
                new ToolInvocation(
                        "getFileContent",
                        Map.of("filePath", APP_JAVA),
                        ToolInvocationCollector.ToolInvocationStatus.OK,
                        null,
                        null,
                        null,
                        null,
                        "{\"project\":\"" + TestProjects.ID + "\",\"path\":\"" + APP_JAVA + "\"}",
                        collector.nextCallIndex()));

        ScriptResult result =
                run(
                        "kb.edit('src/App.java', 'class App', 'class SeenElsewhere'); return 'ok';",
                        collector);

        assertThat(result.error()).isNull();
        assertThat(fileText(APP_JAVA)).startsWith("class SeenElsewhere");
    }

    /**
     * The same session guard also covers a second {@code runScript} call: a file an earlier script
     * only read (never edited) still shows up in that call's own result as {@code filesRead}, and a
     * later script in the same response can build on it without reading the file all over again.
     */
    @Test
    void acceptsAFileReadByAnEarlierRunScriptCallInTheSameResponse() {
        ToolInvocationCollector collector = new ToolInvocationCollector();
        collector.record(
                new ToolInvocation(
                        "runScript",
                        Map.of("script", "kb.read('src/App.java'); return 'ok';"),
                        ToolInvocationCollector.ToolInvocationStatus.OK,
                        null,
                        null,
                        null,
                        null,
                        "{\"project\":\""
                                + TestProjects.ID
                                + "\",\"value\":\"ok\",\"filesRead\":[\"src/App.java\"]}",
                        collector.nextCallIndex()));

        ScriptResult result =
                run(
                        "kb.edit('src/App.java', 'class App', 'class FromEarlierScript'); return"
                                + " 'ok';",
                        collector);

        assertThat(result.error()).isNull();
        assertThat(fileText(APP_JAVA)).startsWith("class FromEarlierScript");
    }

    /**
     * A read of the same path is not evidence when it came from another repository: {@code
     * getFileContent}'s own {@code project} override let the model read a same-named file in a
     * different project, and that read must not stand in for this run's own file.
     */
    @Test
    void aReadOfTheSamePathFromAnotherProjectDoesNotCount() {
        ToolInvocationCollector collector = new ToolInvocationCollector();
        collector.record(
                new ToolInvocation(
                        "getFileContent",
                        Map.of("filePath", APP_JAVA, "project", "billing"),
                        ToolInvocationCollector.ToolInvocationStatus.OK,
                        null,
                        null,
                        null,
                        null,
                        "{\"project\":\"billing\",\"path\":\"" + APP_JAVA + "\"}",
                        collector.nextCallIndex()));

        ScriptResult result =
                run("kb.edit('src/App.java', 'class App', 'class Blind'); return 'ok';", collector);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    /** Only a completed read counts — a call still in flight has not shown the model anything. */
    @Test
    void ignoresAToolCallStillInFlight() {
        ToolInvocationCollector collector = new ToolInvocationCollector();
        collector.record(
                new ToolInvocation(
                        "getFileContent",
                        Map.of("filePath", APP_JAVA),
                        ToolInvocationCollector.ToolInvocationStatus.STARTED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        collector.nextCallIndex()));

        ScriptResult result =
                run("kb.edit('src/App.java', 'class App', 'class Blind'); return 'ok';", collector);

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

    // ── Binary files ────────────────────────────────────────────────────────

    /**
     * Bytes are written whole, never by fragment: {@code kb.edit}'s exact-match contract is defined
     * on text, and an offset into a binary file carries none of the evidence that contract exists
     * to demand. What survives is the read rule — see {@link
     * #refusesToOverwriteBytesOfAFileTheScriptHasNotRead}.
     */
    @Test
    void replacesTheBytesOfABinaryFile() {
        ScriptResult result =
                run(
                        """
                        var bytes = kb.readBytes('static/logo.png');
                        bytes[bytes.length - 1] = 42;
                        return kb.writeBytes('static/logo.png', bytes);
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileBytes("static/logo.png"))
                .hasSize(PNG.length)
                .endsWith(new byte[] {42})
                .startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        assertThat(result.value())
                .isEqualTo(
                        Map.of(
                                "path",
                                "static/logo.png",
                                "operation",
                                "write",
                                "bytes",
                                PNG.length));
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path, GitEditResult::operation)
                .containsExactly("static/logo.png", "edit");
        // No line diff for a binary change — git's own answer instead, which the chat renders as a
        // diff header rather than as a change of zero lines.
        assertThat(result.edits().getFirst().diff()).contains("Binary files", "differ");
    }

    @Test
    void createsABinaryFileFromBase64() {
        ScriptResult result =
                run(
                        """
                        var copy = kb.readBase64('static/logo.png');
                        return kb.createBytes('static/copy.png', copy);
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileBytes("static/copy.png")).isEqualTo(PNG);
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path, GitEditResult::operation)
                .containsExactly("static/copy.png", "create");
    }

    /** The whole point of buffering, on the byte path too: a run that fails writes nothing. */
    @Test
    void writesNoBytesWhenTheScriptThrowsAfterWriting() {
        ScriptResult result =
                run(
                        """
                        kb.readBytes('static/logo.png');
                        kb.writeBytes('static/logo.png', [1, 2, 3]);
                        kb.createBytes('static/new.bin', [4, 5]);
                        throw new Error('boom');
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
        assertThat(repoDir.resolve("static/new.bin")).doesNotExist();
    }

    @Test
    void refusesToOverwriteBytesOfAFileTheScriptHasNotRead() {
        ScriptResult result = run("kb.writeBytes('static/logo.png', [1, 2, 3]); return 'ok';");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    /**
     * The pair has to work in one run: {@code kb.createBytes} stages a file that is on no disk and
     * in no index, so the tracked-file check every replace makes would refuse the very file the
     * script has just created — while {@code kb.createBytes} refuses a second time round, leaving
     * nowhere to go. The text pair ({@code kb.create} + {@code kb.edit}) has never had that gap.
     */
    @Test
    void writesBytesAgainToAFileThisRunCreated() {
        ScriptResult result =
                run(
                        """
                        kb.createBytes('static/icon.ico', [0, 1]);
                        kb.writeBytes('static/icon.ico', [0, 1, 2, 3]);
                        return 'ok';
                        """);

        assertThat(result.error()).isNull();
        assertThat(fileBytes("static/icon.ico")).containsExactly(0, 1, 2, 3);
        // One file, and still a creation — the second write did not turn it into an edit of
        // something that was never there.
        assertThat(result.edits())
                .singleElement()
                .extracting(GitEditResult::path, GitEditResult::operation)
                .containsExactly("static/icon.ico", "create");
    }

    /**
     * The other half of "bytes are written whole": on a text file that would be a whole-file
     * replacement with no exact match behind it and no diff in front of it — the user would be
     * asked to review "binary files differ" over a file they can read. Exactly what {@code
     * kb.edit}'s contract exists to prevent, so it is refused and {@code kb.edit} is named.
     */
    @Test
    void refusesToOverwriteATextFileWithBytes() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.writeBytes('src/App.java', 'Ly8gd2lwZWQK');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("text file", "kb.edit");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    /**
     * The "this run created it" exemption is about creation, not about having staged something: a
     * text file the run merely edited is still on disk, still text, and still the thing the binary
     * rule protects — otherwise {@code kb.edit} followed by {@code kb.writeBytes} would be the
     * two-step way round it.
     */
    @Test
    void refusesToOverwriteWithBytesATextFileThisRunOnlyEdited() {
        ScriptResult result =
                run(
                        """
                        kb.read('src/App.java');
                        kb.edit('src/App.java', 'class App', 'class Edited');
                        kb.writeBytes('src/App.java', 'Ly8gd2lwZWQK');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("text file", "kb.edit");
        assertThat(fileText(APP_JAVA)).isEqualTo(ORIGINAL);
    }

    /**
     * {@code requireRead} is the only guard a byte write has, so what satisfies it must really be a
     * look at the file. A window that starts past the end returns an empty array — the script has
     * been shown nothing, and asking for one must not unlock overwriting the whole file.
     */
    @Test
    void anEmptyByteWindowDoesNotCountAsHavingSeenTheFile() {
        ScriptResult result =
                run(
                        """
                        kb.readBytes('static/logo.png', 999999, 1);
                        kb.writeBytes('static/logo.png', [7]);
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    /**
     * A digest is not the content. {@code kb.hash} reads the whole file and hands back 64
     * characters, which tells a script that two files differ but never what is in either — so it
     * cannot stand in for having looked at one.
     */
    @Test
    void aHashDoesNotCountAsHavingSeenTheFile() {
        ScriptResult result =
                run("kb.hash('static/logo.png'); kb.writeBytes('static/logo.png', [1]); return 1;");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("has not looked at it");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    @Test
    void refusesToEditAsTextWhatThisRunWroteAsBytes() {
        ScriptResult result =
                run(
                        """
                        kb.readBytes('static/logo.png');
                        kb.writeBytes('static/logo.png', [1, 2, 3]);
                        kb.edit('static/logo.png', 'PNG', 'JPG');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("already wrote raw bytes", "kb.writeBytes");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    @Test
    void refusesToEditABinaryFileAsTextAndNamesTheByteWrite() {
        ScriptResult result =
                run(
                        """
                        kb.readBytes('static/logo.png');
                        kb.edit('static/logo.png', 'PNG', 'JPG');
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("binary file", "kb.writeBytes");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    @Test
    void refusesContentThatIsNeitherBase64NorBytes() {
        ScriptResult result =
                run("kb.readBytes('static/logo.png'); kb.writeBytes('static/logo.png', [1, 999]);");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("not a byte value");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
    }

    @Test
    void countsByteWritesAgainstTheSameWriteBudgets() {
        runner = newRunner(true, withEditLimits(1));

        ScriptResult result =
                run(
                        """
                        kb.readBytes('static/logo.png');
                        kb.writeBytes('static/logo.png', [1, 2, 3]);
                        kb.createBytes('static/second.bin', [4]);
                        return 'ok';
                        """);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("maxEditedFiles");
        assertThat(fileBytes("static/logo.png")).isEqualTo(PNG);
        assertThat(repoDir.resolve("static/second.bin")).doesNotExist();
    }

    // ── Where writes are not available at all ───────────────────────────────

    @Test
    void doesNotBindTheWriteMethodsWhenEditingIsDisabled() {
        runner = newRunner(false, ScriptProperties.enabledWithDefaults());

        assertThat(run("return typeof kb.edit;").value()).isEqualTo("undefined");
        assertThat(run("return typeof kb.create;").value()).isEqualTo("undefined");
        assertThat(run("return typeof kb.writeBytes;").value()).isEqualTo("undefined");
        assertThat(run("return typeof kb.createBytes;").value()).isEqualTo("undefined");
        // Reading still works — only the writes are gone, bytes included.
        assertThat(run("return kb.read('src/App.java').length > 0;").value()).isEqualTo(true);
        assertThat(run("return kb.readBytes('static/logo.png').length > 0;").value())
                .isEqualTo(true);
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

    private ScriptResult run(String script, ToolInvocationCollector priorInvocations) {
        return runner.run(script, null, RunCancellation.none(), false, priorInvocations, null);
    }

    private ScriptRunner newRunner(boolean editEnabled, ScriptProperties properties) {
        GitRegistry gitRegistry = TestProjects.registry(repoDir, editEnabled);
        return new ScriptRunner(
                gitRegistry, null, properties, new ScriptEditPolicy(gitRegistry, properties));
    }

    private static ScriptProperties withEditLimits(int maxEditedFiles) {
        return new ScriptProperties(
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
                        DataSize.ofKilobytes(256)));
    }

    private String fileText(String relativePath) {
        try {
            return Files.readString(repoDir.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] fileBytes(String relativePath) {
        try {
            return Files.readAllBytes(repoDir.resolve(relativePath));
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

    private static void writeBytes(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
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
