package io.github.trialiya.kb.service.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * What a script may and may not do. The security claim of {@code runScript} is not "the filesystem
 * is restricted" but "there is no filesystem" — every escape below has to fail because the API it
 * needs does not exist in the context, not because something filtered it.
 */
class ScriptSandboxTest {

    @TempDir Path repoDir;

    @TempDir Path outsideDir;

    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve("src/App.java"), "class App {\n  void run() {}\n}\n");
        write(repoDir.resolve("docs/readme.md"), "hello\nworld\n");
        write(repoDir.resolve("secret.pem"), "PRIVATE KEY");
        write(outsideDir.resolve("passwd"), "root:x:0:0");
        commitAll();
        runner = newRunner(ScriptProperties.enabledWithDefaults());
    }

    private ScriptRunner newRunner(ScriptProperties properties) {
        GitService gitService =
                new GitService(new GitProperties(repoDir.toString(), false), new OutlineService());
        return new ScriptRunner(gitService, null, properties);
    }

    private ScriptResult run(String script) {
        return runner.run(script, null, RunCancellation.none());
    }

    // ── The sandbox has no way out ──────────────────────────────────────────

    @Test
    void cannotReachHostClasses() {
        // GraalJS always defines the `Java` namespace object; what makes it harmless is that host
        // class lookup is denied, so nothing reachable through it can produce a host class.
        List<String> attempts =
                List.of(
                        "Java.type('java.io.File')",
                        "Java.type('java.lang.Runtime')",
                        "Java.type('java.nio.file.Files')",
                        "Java.addToClasspath('/tmp')");
        for (String attempt : attempts) {
            assertThat(run("return String(" + attempt + ");").error())
                    .describedAs("%s must fail", attempt)
                    .isNotNull()
                    .extracting(ScriptError::kind)
                    .isEqualTo(ScriptError.Kind.RUNTIME);
        }
    }

    @Test
    void hasNoFilesystemNetworkOrModuleApis() {
        List<String> absent =
                List.of(
                        "require",
                        "fetch",
                        "XMLHttpRequest",
                        "Worker",
                        "importScripts",
                        "Polyglot");
        for (String name : absent) {
            assertThat(run("return typeof " + name + ";").value())
                    .describedAs("%s must not exist in the sandbox", name)
                    .isEqualTo("undefined");
        }
    }

    @Test
    void cannotEscapeTheRepositoryThroughKbRead() {
        for (String path : List.of("../passwd", "/etc/passwd", outsideDir + "/passwd")) {
            ScriptResult result = run("return kb.read(" + quote(path) + ");");
            assertThat(result.error())
                    .describedAs("reading %s must fail", path)
                    .isNotNull()
                    .extracting(ScriptError::kind)
                    .isEqualTo(ScriptError.Kind.RUNTIME);
        }
    }

    @Test
    void cannotReadUntrackedFiles() {
        write(repoDir.resolve("untracked.txt"), "not in the index");

        assertThat(run("return kb.read('untracked.txt');").error()).isNotNull();
    }

    // ── The configured policy ───────────────────────────────────────────────

    @Test
    void denyGlobsHideFilesFromListingReadingAndGrep() {
        runner = newRunner(withGlobs(List.of("**/*.pem"), List.of()));

        assertThat(files()).contains("src/App.java").doesNotContain("secret.pem");
        assertThat(run("return kb.read('secret.pem');").error())
                .isNotNull()
                .extracting(ScriptError::message)
                .asString()
                // Same wording as a genuinely missing file: the policy must not be probeable.
                .contains("File not found");
        assertThat(run("return kb.grep('PRIVATE').length;").value()).isEqualTo(0);
    }

    @Test
    void allowGlobsNarrowTheVisibleTreeToTheWhitelist() {
        runner = newRunner(withGlobs(List.of(), List.of("docs/**")));

        assertThat(files()).containsExactly("docs/readme.md");
        assertThat(run("return kb.read('src/App.java');").error()).isNotNull();
        assertThat(run("return kb.read('docs/readme.md');").value()).isEqualTo("hello\nworld\n");
    }

    @Test
    void anEmptyPolicyHidesNothingBeyondGitsOwnRules() {
        assertThat(files()).contains("src/App.java", "docs/readme.md", "secret.pem");
    }

    // ── Budgets ─────────────────────────────────────────────────────────────

    @Test
    void stopsAScriptThatReadsTooManyFiles() {
        runner = newRunner(withLimits(limits -> limits.withMaxFilesRead(1)));

        ScriptResult result =
                run("var p = kb.files(); for (var i = 0; i < p.length; i++) { kb.read(p[i]); }");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxFilesRead");
    }

    @Test
    void stopsAScriptThatMakesTooManyCalls() {
        runner = newRunner(withLimits(limits -> limits.withMaxCalls(5)));

        ScriptResult result = run("for (var i = 0; i < 1000; i++) { kb.log(i); } return 'done';");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxCalls");
    }

    @Test
    void refusesToReturnMoreThanTheResultBudget() {
        runner = newRunner(withLimits(limits -> limits.withMaxResultChars(64)));

        ScriptResult result =
                run("var s = ''; for (var i = 0; i < 500; i++) { s += 'x'; } return s;");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxResultChars");
    }

    @Test
    void aBudgetErrorIsCatchableSoAScriptCanReturnPartialResults() {
        runner = newRunner(withLimits(limits -> limits.withMaxFilesRead(1)));

        ScriptResult result =
                run(
                        """
                        var read = 0;
                        var paths = kb.files();
                        try {
                          for (var i = 0; i < paths.length; i++) { kb.read(paths[i]); read++; }
                        } catch (e) {
                          return { partial: true, read: read };
                        }
                        return { partial: false, read: read };
                        """);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(Map.of("partial", true, "read", 1));
    }

    // ── Failure reporting ───────────────────────────────────────────────────

    @Test
    void reportsSyntaxErrorsWithTheLineTheModelWrote() {
        ScriptResult result = run("var a = 1;\nvar b = ;\nreturn a;");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.SYNTAX);
        assertThat(result.error().line()).isEqualTo(2);
    }

    @Test
    void reportsRuntimeErrorsWithoutLosingWhatTheScriptAlreadyLogged() {
        ScriptResult result = run("kb.log('before the throw');\nthrow new Error('boom');");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("boom");
        assertThat(result.log()).containsExactly("before the throw");
    }

    @Test
    void stopsAnInfiniteLoopAtTheTimeout() {
        runner =
                newRunner(
                        new ScriptProperties(
                                true,
                                null,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                Duration.ofMillis(20),
                                null,
                                null,
                                null));

        long start = System.nanoTime();
        ScriptResult result = run("while (true) {}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.TIMEOUT);
        assertThat(elapsedMs).isLessThan(10_000);
    }

    // ── The API itself ──────────────────────────────────────────────────────

    @Test
    void readsLineRangesAndReportsWhatItTouched() {
        ScriptResult result = run("return kb.read('src/App.java', 2, 2);");

        assertThat(result.value()).isEqualTo("  void run() {}");
        assertThat(result.filesRead()).containsExactly("src/App.java");
        assertThat(result.stats().filesRead()).isEqualTo(1);
    }

    @Test
    void grepReturnsPlainJsObjectsAScriptCanIterate() {
        ScriptResult result =
                run(
                        """
                        var hits = kb.grep('void run', { glob: '**/*.java' });
                        return hits.map(function (h) { return h.path + ':' + h.line; });
                        """);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(List.of("src/App.java:2"));
    }

    @Test
    void outlineExposesSymbolsWithTheirLineRanges() {
        ScriptResult result =
                run("return kb.outline('src/App.java').map(function (s) { return s.name; });");

        assertThat(result.error()).isNull();
        assertThat((List<Object>) result.value()).contains("App");
    }

    @Test
    void logsStringsAsTheyAreAndObjectsAsJson() {
        ScriptResult result = run("kb.log('plain'); kb.log({ a: 1 }); return null;");

        assertThat(result.log()).containsExactly("plain", "{\"a\":1}");
    }

    @Test
    void aScriptThatReturnsNothingIsNotAnError() {
        ScriptResult result = run("kb.log('side effect only');");

        assertThat(result.error()).isNull();
        assertThat(result.value()).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> files() {
        return (List<String>) run("return kb.files();").value();
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static ScriptProperties withGlobs(List<String> deny, List<String> allow) {
        return new ScriptProperties(true, null, null, null, null, null, deny, allow);
    }

    private static ScriptProperties withLimits(
            java.util.function.UnaryOperator<LimitsBuilder> tune) {
        return new ScriptProperties(
                true, null, null, null, null, tune.apply(new LimitsBuilder()).build(), null, null);
    }

    /** Mutable stand-in for {@link ScriptProperties.Limits}, so a test can vary one budget. */
    private static final class LimitsBuilder {
        private int maxFilesRead = 200;
        private int maxCalls = 2000;
        private int maxResultChars = 20_000;

        LimitsBuilder withMaxFilesRead(int value) {
            this.maxFilesRead = value;
            return this;
        }

        LimitsBuilder withMaxCalls(int value) {
            this.maxCalls = value;
            return this;
        }

        LimitsBuilder withMaxResultChars(int value) {
            this.maxResultChars = value;
            return this;
        }

        ScriptProperties.Limits build() {
            return new ScriptProperties.Limits(
                    maxFilesRead,
                    DataSize.ofMegabytes(4),
                    DataSize.ofKilobytes(512),
                    500,
                    maxCalls,
                    20_000,
                    maxResultChars);
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
