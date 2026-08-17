package io.github.trialiya.kb.service.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.OutlineService;
import io.github.trialiya.kb.tools.RunCancellation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
        writeBytes(repoDir.resolve("static/logo.png"), PNG);
        write(outsideDir.resolve("passwd"), "root:x:0:0");
        commitAll();
        runner = newRunner(ScriptProperties.enabledWithDefaults());
    }

    private ScriptRunner newRunner(ScriptProperties properties) {
        return newRunner(properties, null);
    }

    private ScriptRunner newRunner(
            ScriptProperties properties, @Nullable DocumentService documentService) {
        GitProperties gitProperties = new GitProperties(repoDir.toString(), false);
        GitService gitService = new GitService(gitProperties, new OutlineService());
        return new ScriptRunner(
                gitService,
                documentService,
                properties,
                new ScriptEditPolicy(gitProperties, properties, gitService));
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

        assertThat(run("return kb.read('untracked.txt');").error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.RUNTIME);
    }

    // ── The configured policy ───────────────────────────────────────────────

    @Test
    void denyGlobsHideFilesFromListingReadingAndGrep() {
        runner = newRunner(withGlobs(List.of("**/*.pem"), List.of()));

        assertThat(files()).contains("src/App.java").doesNotContain("secret.pem");

        ScriptError denied = run("return kb.read('secret.pem');").error();
        // Indistinguishable from a genuinely missing file — same wording AND same kind, so the
        // policy cannot be probed and the model is not told to narrow a glob it never hit.
        assertThat(denied).isNotNull();
        assertThat(denied.message()).contains("File not found");
        assertThat(denied.kind()).isEqualTo(ScriptError.Kind.RUNTIME);

        assertThat(run("return kb.grep('PRIVATE').length;").value()).isEqualTo(0);
    }

    @Test
    void allowGlobsNarrowTheVisibleTreeToTheWhitelist() {
        runner = newRunner(withGlobs(List.of(), List.of("docs/**")));

        assertThat(files()).containsExactly("docs/readme.md");
        assertThat(run("return kb.read('src/App.java');").error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(run("return kb.read('docs/readme.md');").value()).isEqualTo("hello\nworld\n");
    }

    @Test
    void anEmptyPolicyHidesNothingBeyondGitsOwnRules() {
        assertThat(files()).contains("src/App.java", "docs/readme.md", "secret.pem");
    }

    /**
     * The policy is a string match, so it is only as good as the spelling it is matched against:
     * {@code ./secret.pem} names the same file and misses a glob written the obvious way. On the
     * read path a denied respelling was already stopped one layer down, by the tracked-files rule —
     * this pins that the policy itself stops it, which is what the write path relies on (see {@code
     * ScriptEditTest}).
     */
    @Test
    void aDeniedFileStaysDeniedHoweverThePathIsSpelled() {
        runner = newRunner(withGlobs(List.of("secret.pem"), List.of()));

        for (String spelling : List.of("secret.pem", "./secret.pem", ".//./secret.pem")) {
            ScriptError denied = run("return kb.read(" + quote(spelling) + ");").error();
            assertThat(denied).as(spelling).isNotNull();
            assertThat(denied.message()).as(spelling).contains("File not found");
            assertThat(denied.kind()).as(spelling).isEqualTo(ScriptError.Kind.RUNTIME);
        }
    }

    /**
     * The flip side, and the one a model actually hits: an equivalent spelling of a file it may
     * read has to just work. A leading {@code ./} is a natural thing to write and used to be a dead
     * end indistinguishable from the file not existing.
     */
    @Test
    void anAllowedFileReadsUnderAnyEquivalentSpelling() {
        assertThat(run("return kb.read('./docs/readme.md');").value()).isEqualTo("hello\nworld\n");
        assertThat(run("return kb.read('docs//readme.md');").value()).isEqualTo("hello\nworld\n");
        // And it is booked once, under one name, however it was asked for.
        assertThat(
                        run("kb.read('./docs/readme.md'); kb.read('docs/readme.md'); return 1;")
                                .filesRead())
                .containsExactly("docs/readme.md");
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

    // ── The in-run cache ────────────────────────────────────────────────────

    /**
     * The motivating case: a script that compares many files against many names naturally writes
     * the file loop inside the name loop, re-reading the same handful of files once per name. That
     * used to be indistinguishable from a genuine runaway loop — same file, over and over, one
     * {@code kb.*} call per repetition — and could exhaust {@code maxCalls} before the script
     * produced anything. A memoized repeat costs nothing, so the same script now finishes.
     */
    @Test
    void repeatedIdenticalReadsDoNotSpendTheCallOrByteBudget() {
        runner =
                newRunner(
                        withLimits(
                                limits ->
                                        limits.withMaxCalls(10)
                                                .withMaxBytesRead(DataSize.ofBytes(100))));

        ScriptResult result =
                run(
                        "var names = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];"
                                + "var hits = 0;"
                                + "for (var i = 0; i < names.length; i++) {"
                                + "  var text = kb.read('src/App.java');"
                                + "  if (text.indexOf(names[i]) >= 0) { hits++; }"
                                + "}"
                                + "return hits;");

        assertThat(result.error()).isNull();
        // One real read charged; the other seven names' reads were answered from the cache.
        assertThat(result.stats().filesRead()).isEqualTo(1);
        assertThat(result.stats().calls()).isEqualTo(1);
    }

    /**
     * Same path, different arguments — grep options, read ranges — must not collide in the cache.
     */
    @Test
    void differentArgumentsToTheSameCallAreNotTreatedAsTheSameCall() {
        ScriptResult result =
                run(
                        "var whole = kb.read('src/App.java');"
                                + "var head = kb.read('src/App.java', 1, 1);"
                                + "return { whole: whole.length, head: head };");

        assertThat(result.error()).isNull();
        assertThat(result.stats().calls()).isEqualTo(2);
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertThat(value.get("head")).isEqualTo("class App {");
    }

    /**
     * {@code ProxyArray}/{@code ProxyObject} write through to their backing Java collection, so a
     * cached result handed out by reference would let one call's in-place mutation (sort, in this
     * case) corrupt what a later identical call gets back. Each call must see its own copy.
     */
    @Test
    void mutatingOneCallsResultDoesNotAffectTheNextIdenticalCall() {
        write(repoDir.resolve("b.txt"), "zzz\n");
        write(repoDir.resolve("a.txt"), "aaa\n");
        commitAll();

        ScriptResult result =
                run(
                        "var first = kb.files('*.txt');"
                                + "first.sort(function (a, b) { return b < a ? -1 : 1; });"
                                + "var second = kb.files('*.txt');"
                                + "return { first: first, second: second };");

        assertThat(result.error()).isNull();
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertThat(value.get("second")).isEqualTo(List.of("a.txt", "b.txt"));
    }

    /**
     * A search returns file content, so it spends the content budget. Left unmetered it was the one
     * way to pull an unbounded amount of the repository into a script while every other budget
     * stayed unspent — {@code kb.read} is capped, but a loop of searches was free.
     */
    @Test
    void searchResultsCountAgainstTheByteBudget() {
        runner = newRunner(withLimits(limits -> limits.withMaxBytesRead(DataSize.ofBytes(40))));

        // {max: i + 1} varies the call's own arguments, so each iteration is a genuinely new call
        // rather than one the run's cache (see ScriptSession.call) would answer for free.
        ScriptResult result =
                run(
                        "for (var i = 0; i < 100; i++) { kb.grep('class', { max: i + 1 }); } return"
                                + " 'done';");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxBytesRead");
        // Charged as bytes, not as files — a match line is not the file it came from.
        assertThat(result.stats().filesRead()).isZero();
        assertThat(result.stats().bytesRead()).isPositive();
    }

    /**
     * The same hole as {@link #searchResultsCountAgainstTheByteBudget}, on the other search: {@code
     * kb.searchDocs} hands back document text too, so a loop of document searches must not be a way
     * to fill a script with content while every other budget stays unspent.
     */
    @Test
    void documentSearchResultsCountAgainstTheByteBudget() {
        DocumentService documents = mock(DocumentService.class);
        when(documents.hybridSearch(any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new SearchResult(
                                        1L,
                                        "Экспорт документов",
                                        "x".repeat(200),
                                        LocalDateTime.now(),
                                        null,
                                        null)));
        runner =
                newRunner(
                        withLimits(limits -> limits.withMaxBytesRead(DataSize.ofBytes(40))),
                        documents);

        // Varying the limit argument is what keeps each iteration a genuinely new call — see
        // ScriptSession.call — rather than the run's cache answering repeats for free.
        ScriptResult result =
                run(
                        "for (var i = 0; i < 100; i++) { kb.searchDocs('экспорт', i + 1); } return"
                                + " 'done';");

        assertThat(result.error())
                .isNotNull()
                .extracting(ScriptError::kind)
                .isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxBytesRead");
        // Charged as bytes, not as files — a snippet is not a file of the repository.
        assertThat(result.stats().filesRead()).isZero();
        assertThat(result.stats().bytesRead()).isPositive();
    }

    @Test
    void truncatesResultsOverTheResultBudgetWithAWarningInsteadOfFailing() {
        runner = newRunner(withLimits(limits -> limits.withMaxResultChars(64)));

        ScriptResult result =
                run("var s = ''; for (var i = 0; i < 500; i++) { s += 'x'; } return s;");

        assertThat(result.error()).isNull();
        assertThat(result.value()).asInstanceOf(STRING).hasSize(64);
        assertThat(result.log()).anySatisfy(line -> assertThat(line).contains("maxResultChars"));
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
                                false,
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

    /**
     * {@code GitService} answers an oversized whole-file read with a head+tail excerpt. For a
     * person that is a courtesy; a script would go on to count over a file with its middle missing
     * and report the answer as fact, so the excerpt is refused and the exact-text call is named.
     */
    @Test
    void refusesAWholeFileReadThatWouldComeBackExcerpted() {
        write(repoDir.resolve("big.txt"), "line\n".repeat(200_000));
        commitAll();

        ScriptResult whole = run("return kb.read('big.txt').length;");

        assertThat(whole.error()).isNotNull();
        assertThat(whole.error().kind()).isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(whole.error().message()).contains("kb.read(path, from, to)");

        // The advice it gives has to work: a range out of the same file is exact and allowed.
        ScriptResult range = run("return kb.read('big.txt', 1, 2);");

        assertThat(range.error()).isNull();
        assertThat(range.value()).isEqualTo("line\nline");
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

    // ── Binary files ────────────────────────────────────────────────────────

    /**
     * A binary file is not an off-limits file, only one that cannot be decoded as text: everything
     * a script can do to a text file it can do to this one, through the byte-level half of the API.
     * The tracked-files rule and the glob policy still decide <em>which</em> files — see {@link
     * #binaryFilesObeyTheSameVisibilityPolicyAsTextOnes}.
     */
    @Test
    void readsTheBytesOfABinaryFile() {
        ScriptResult result =
                run(
                        """
                        var bytes = kb.readBytes('static/logo.png');
                        return { length: bytes.length, head: bytes.slice(0, 4) };
                        """);

        assertThat(result.error()).isNull();
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertThat(value.get("length")).isEqualTo(PNG.length);
        // 0x89 arrives as 137, not as -119: a byte reaches the script unsigned, the way every JS
        // API that deals in bytes presents it.
        assertThat(value.get("head")).isEqualTo(List.of(137, 80, 78, 71));
        assertThat(result.filesRead()).containsExactly("static/logo.png");
    }

    @Test
    void readsTheSameBytesAsBase64() {
        ScriptResult result = run("return kb.readBase64('static/logo.png');");

        assertThat(result.error()).isNull();
        assertThat(Base64.getDecoder().decode((String) result.value())).isEqualTo(PNG);
    }

    @Test
    void readsOneWindowOfBytesWithoutTheRest() {
        ScriptResult result = run("return kb.readBytes('static/logo.png', 1, 3);");

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(List.of(80, 78, 71));
        // The window is charged, not the file: three bytes, not the whole of it.
        assertThat(result.stats().bytesRead()).isEqualTo(3);
    }

    @Test
    void statAnswersWhetherAFileIsBinaryWithoutReadingIt() {
        ScriptResult result =
                run(
                        """
                        return {
                          png: kb.stat('static/logo.png').binary,
                          java: kb.stat('src/App.java').binary,
                          size: kb.stat('static/logo.png').size,
                          lang: kb.stat('src/App.java').language
                        };
                        """);

        assertThat(result.error()).isNull();
        assertThat(result.value())
                .isEqualTo(Map.of("png", true, "java", false, "size", PNG.length, "lang", "java"));
        // Metadata is not content: nothing was read, so nothing was charged as read either.
        assertThat(result.stats().filesRead()).isZero();
        assertThat(result.stats().bytesRead()).isZero();
    }

    /**
     * {@code kb.read} still refuses — decoding these bytes as UTF-8 would hand back a string that
     * no longer round-trips — but it now refuses by naming the two calls that do serve them, which
     * is the difference between a dead end and a redirect.
     */
    @Test
    void readRefusesBinaryContentByNamingTheByteMethods() {
        ScriptResult result = run("return kb.read('static/logo.png');");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("kb.readBytes", "kb.readBase64");
    }

    /**
     * A digest is not content, and the run must not claim otherwise. {@code filesRead} is
     * serialised into the tool result, where {@code ToolInvocationCollector.hasSeenFile} reads it
     * as evidence that the model has looked at the file — so a hashed path reported there would let
     * a <em>later</em> tool call in the same response edit a file nobody ever opened.
     */
    @Test
    void hashesAFileWithoutReportingItAsRead() {
        ScriptResult result = run("return kb.hash('static/logo.png');");

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(sha256(PNG));
        assertThat(result.filesRead()).isEmpty();
        assertThat(result.stats().filesRead()).isZero();
        assertThat(result.stats().bytesRead()).isZero();
    }

    /** It is still a whole pass over the file, so it still costs one against the file budget. */
    @Test
    void hashingCountsAgainstTheFileBudget() {
        runner = newRunner(withLimits(limits -> limits.withMaxFilesRead(1)));

        ScriptResult result =
                run("kb.hash('static/logo.png'); kb.hash('src/App.java'); return 'ok';");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxFilesRead");
    }

    /**
     * A byte reaches the script as a JS number, so one call hands over far less than the run's byte
     * budget would allow. The refusal has to name the way out, and that way out has to work.
     */
    @Test
    void refusesToHandOverAWholeFileTooLargeForOneCallAndNamesTheWindow() {
        writeBytes(repoDir.resolve("static/big.bin"), new byte[300_000]);
        commitAll();

        ScriptResult whole = run("return kb.readBytes('static/big.bin').length;");

        assertThat(whole.error()).isNotNull();
        assertThat(whole.error().kind()).isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(whole.error().message()).contains("kb.readBytes(path, offset, length)");

        ScriptResult window = run("return kb.readBytes('static/big.bin', 299_990, 100).length;");

        assertThat(window.error()).isNull();
        // Clamped to what is actually left, rather than refused for asking past the end.
        assertThat(window.value()).isEqualTo(10);
    }

    /**
     * A window that starts past the end of the file is empty rather than an error — but it hands
     * the script nothing, so it is not reported as a read either (what that would cost is in {@code
     * ScriptEditTest}).
     */
    @Test
    void aWindowPastTheEndOfTheFileIsEmptyAndCountsAsNoContent() {
        ScriptResult result = run("return kb.readBytes('static/logo.png', 999999, 1).length;");

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(0);
        assertThat(result.filesRead()).isEmpty();
        assertThat(result.stats().bytesRead()).isZero();
    }

    @Test
    void binaryFilesObeyTheSameVisibilityPolicyAsTextOnes() {
        runner = newRunner(withGlobs(List.of("static/**"), List.of()));

        for (String call :
                List.of(
                        "kb.readBytes('static/logo.png')",
                        "kb.readBase64('static/logo.png')",
                        "kb.stat('static/logo.png')",
                        "kb.hash('static/logo.png')")) {
            ScriptError denied = run("return " + call + ";").error();
            assertThat(denied).as(call).isNotNull();
            assertThat(denied.message()).as(call).contains("File not found");
            assertThat(denied.kind()).as(call).isEqualTo(ScriptError.Kind.RUNTIME);
        }
    }

    @Test
    void cannotReachOutsideTheRepositoryThroughTheByteMethods() {
        for (String path : List.of("../passwd", "/etc/passwd", outsideDir + "/passwd")) {
            ScriptResult result = run("return kb.readBytes(" + quote(path) + ");");
            assertThat(result.error())
                    .describedAs("reading %s must fail", path)
                    .isNotNull()
                    .extracting(ScriptError::kind)
                    .isEqualTo(ScriptError.Kind.RUNTIME);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A PNG header followed by NUL bytes — sniffs binary exactly as git's own heuristic does. */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> files() {
        return (List<String>) run("return kb.files();").value();
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static ScriptProperties withGlobs(List<String> deny, List<String> allow) {
        return new ScriptProperties(
                true, false, null, null, null, null, null, null, null, null, deny, allow);
    }

    private static ScriptProperties withLimits(
            java.util.function.UnaryOperator<LimitsBuilder> tune) {
        return new ScriptProperties(
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                tune.apply(new LimitsBuilder()).build(),
                null,
                null);
    }

    /** Mutable stand-in for {@link ScriptProperties.Limits}, so a test can vary one budget. */
    private static final class LimitsBuilder {
        private int maxFilesRead = 2000;
        private int maxCalls = 2000;
        private int maxResultChars = 20_000;
        private DataSize maxBytesRead = DataSize.ofMegabytes(32);

        LimitsBuilder withMaxFilesRead(int value) {
            this.maxFilesRead = value;
            return this;
        }

        LimitsBuilder withMaxBytesRead(DataSize value) {
            this.maxBytesRead = value;
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
                    maxBytesRead,
                    maxCalls,
                    20_000,
                    maxResultChars,
                    20,
                    DataSize.ofKilobytes(256));
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
