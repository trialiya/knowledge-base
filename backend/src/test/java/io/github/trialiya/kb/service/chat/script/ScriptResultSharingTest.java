package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.service.file.git.GitRegistry;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * One script reading what an earlier script of the same chat returned — {@code kb.result(id)},
 * {@code kb.results()} — and the runner keeping each finished run's value for it.
 */
class ScriptResultSharingTest {

    private static final String CHAT = "chat-a";

    @TempDir Path repoDir;

    private InMemoryScriptResultStore store;
    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve("docs/readme.md"), "hello\nworld\n");
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
        store = new InMemoryScriptResultStore();
        runner = newRunner(ScriptProperties.enabledWithDefaults());
    }

    @Test
    void aLaterScriptOfTheChatReadsWhatAnEarlierOneReturned() {
        ScriptResult first = run(ResultScope.keeping(CHAT), "return {n: 3, names: ['a', 'b']};");

        assertThat(first.resultId()).isEqualTo("r1");
        assertThat(first.getResultMeta()).containsEntry("resultId", "r1");

        ScriptResult second =
                run(
                        ResultScope.keeping(CHAT),
                        "const r = kb.result('r1'); return r.n + r.names.length"
                                + " + r.names.filter(x => x === 'b').length;");

        assertThat(second.error()).isNull();
        assertThat(second.value()).isEqualTo(6);
        assertThat(second.resultId()).isEqualTo("r2");
    }

    @Test
    void theKeptValueIsWholeWhereTheModelWasShownOnlyItsHead() {
        runner = newRunner(withLimits(new ScriptProperties.Limits(0, null, 0, 0, 50, 0, null)));

        ScriptResult big =
                run(ResultScope.keeping(CHAT), "return Array.from({length: 100}, (_, i) => i);");

        // The model's copy is cut to max-result-chars — no longer valid JSON, so it is text.
        assertThat(big.value()).isInstanceOf(String.class);
        assertThat((String) big.value()).hasSize(50);
        assertThat(big.log())
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("kept as r1")
                                        .contains("kb.result('r1')"));

        ScriptResult next =
                run(
                        ResultScope.keeping(CHAT),
                        "const a = kb.result('r1'); return a[a.length - 1];");

        assertThat(next.value()).isEqualTo(99);
    }

    @Test
    void anotherChatDoesNotSeeTheResultAndIsToldWhatItKeeps() {
        run(ResultScope.keeping(CHAT), "return 1;");

        ScriptResult other = run(ResultScope.keeping("chat-b"), "return kb.result('r1');");

        assertThat(other.error()).isNotNull();
        assertThat(other.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(other.error().message()).contains("No kept result 'r1'").contains("keeps none");
    }

    @Test
    void anUnknownIdNamesTheOnesThatExist() {
        run(ResultScope.keeping(CHAT), "return 1;");
        run(ResultScope.keeping(CHAT), "return 2;");

        ScriptResult missing = run(ResultScope.keeping(CHAT), "return kb.result('r7');");

        assertThat(missing.error().message()).contains("Kept: r1, r2");
    }

    @Test
    void aRunThatBelongsToNoChatKeepsNothingAndReadsNothing() {
        ScriptResult result = runner.run("return 1;", null, RunCancellation.none());

        assertThat(result.resultId()).isNull();
        assertThat(runner.run("return kb.results();", null, RunCancellation.none()).error())
                .extracting(ScriptError::message)
                .asString()
                .contains("belongs to no chat");
    }

    @Test
    void aReadOnlyScopeReadsTheChatButAddsNothingToIt() {
        run(ResultScope.keeping(CHAT), "return 'shared';");

        ScriptResult reader = run(ResultScope.readOnly(CHAT), "return kb.result('r1') + '!';");

        assertThat(reader.value()).isEqualTo("shared!");
        assertThat(reader.resultId()).isNull();
        assertThat(store.list(CHAT)).hasSize(1);
    }

    @Test
    void theListingDescribesEveryKeptResult() {
        runner.run(
                new ScriptRequest(
                        new ScriptSource(
                                "return [1, 2];",
                                "tools/pair.js",
                                new ScriptRunSource(
                                        ScriptRunSource.Kind.PROJECT,
                                        "pair",
                                        "tools/pair.js",
                                        "abc",
                                        Map.of())),
                        ScriptArgs.none(),
                        null,
                        false,
                        null,
                        null,
                        ResultScope.keeping(CHAT)),
                RunCancellation.none());

        ScriptResult listing =
                run(
                        ResultScope.keeping(CHAT),
                        "return kb.results().map(r => [r.id, r.script, r.chars].join(':'));");

        assertThat(listing.value()).isEqualTo(List.of("r1:pair:5"));
    }

    @Test
    void aFailedRunOrOneThatReturnedNothingKeepsNothing() {
        assertThat(run(ResultScope.keeping(CHAT), "throw new Error('no');").resultId()).isNull();
        assertThat(run(ResultScope.keeping(CHAT), "kb.log('only a log');").resultId()).isNull();
        assertThat(run(ResultScope.keeping(CHAT), "return null;").resultId()).isNull();

        assertThat(store.list(CHAT)).isEmpty();
    }

    @Test
    void aScriptThatEditsWhatItReadCannotChangeTheKeptValue() {
        run(ResultScope.keeping(CHAT), "return {n: 3};");

        ScriptResult result =
                run(
                        ResultScope.keeping(CHAT),
                        "const r = kb.result('r1'); r.n = 99; return kb.result('r1').n;");

        assertThat(result.value()).isEqualTo(3);
    }

    @Test
    void readingAKeptResultIsChargedAgainstTheByteBudget() {
        run(ResultScope.keeping(CHAT), "return 'x'.repeat(100);");
        runner =
                newRunner(
                        withLimits(
                                new ScriptProperties.Limits(
                                        0, DataSize.ofBytes(50), 0, 0, 0, 0, null)));

        ScriptResult result = run(ResultScope.keeping(CHAT), "return kb.result('r1').length;");

        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxBytesRead");
    }

    @Test
    void anIdWrittenAnyWayIsOneResultChargedOnceButEveryReadIsACall() {
        run(ResultScope.keeping(CHAT), "return 'x'.repeat(100);");

        ScriptResult result =
                run(
                        ResultScope.keeping(CHAT),
                        "return [kb.result('r1'), kb.result('R1'), kb.result('1')]"
                                + ".map(v => v.length);");

        assertThat(result.value()).isEqualTo(List.of(100, 100, 100));
        assertThat(result.stats().bytesRead()).isEqualTo(102);
        assertThat(result.stats().calls()).isEqualTo(3);
    }

    @Test
    void noIdAtAllIsAskedForByName() {
        ScriptResult result = run(ResultScope.keeping(CHAT), "return kb.result(null);");

        assertThat(result.error().message()).contains("kb.result needs a result id");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ScriptResult run(@Nullable ResultScope scope, String script) {
        return runner.run(
                new ScriptRequest(
                        ScriptSource.inline(script),
                        ScriptArgs.none(),
                        null,
                        false,
                        null,
                        null,
                        scope),
                RunCancellation.none());
    }

    private ScriptRunner newRunner(ScriptProperties properties) {
        GitRegistry gitRegistry = TestProjects.registry(repoDir, false);
        return new ScriptRunner(
                gitRegistry,
                null,
                properties,
                new ScriptEditPolicy(gitRegistry, properties),
                store);
    }

    private static ScriptProperties withLimits(ScriptProperties.Limits limits) {
        return new ScriptProperties(
                true, false, true, false, null, null, null, null, null, null, null, null, limits);
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
