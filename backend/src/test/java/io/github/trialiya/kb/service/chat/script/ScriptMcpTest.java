package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.McpProperties.ScriptAccess;
import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.support.TestProjects;
import io.github.trialiya.kb.tools.McpToolRegistry.ScriptTool;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.UnavailableToolCallback;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.unit.DataSize;

/**
 * MCP tools called from a script: what the {@code mcp} object returns, who gets one at all, and
 * what one run may spend. The tools are stood in for by callbacks answering the way Spring AI's
 * MCP callback does — the content blocks, as JSON.
 */
class ScriptMcpTest {

    private static final ScriptAccess JIRA_READS = new ScriptAccess(List.of("jira_search", "jira_get_*"), false);

    @TempDir
    Path repoDir;

    private GitRegistry gitRegistry;
    private ScriptProperties properties;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        gitRegistry = TestProjects.registry(repoDir, false);
        properties = ScriptProperties.enabledWithDefaults();
    }

    @Test
    void aScriptGetsTheNormalizedAnswerAndReturnsOnlyWhatItNeeds() {
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            calls.add(args);
            return textBlock(
                    "{\"issues\":[{\"key\":\"KB-1\",\"status\":\"Open\"},{\"key\":\"KB-2\",\"status\":\"Done\"}]}");
        })));

        ScriptResult result = run(runner, """
                const r = mcp.call('jira_search', {jql: 'project = KB'});
                return r.issues.filter(i => i.status === 'Open').map(i => i.key);
                """, ScriptMcpScope.CHAT);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo(List.of("KB-1"));
        assertThat(calls).containsExactly("{\"jql\":\"project = KB\"}");
        assertThat(result.stats().mcpCalls()).isEqualTo(1);
        assertThat(result.log()).anyMatch(line -> line.startsWith("mcp.call('jira_search'): "));
        assertThat(result.getResultMeta()).containsEntry("mcpCalls", 1);
    }

    @Test
    void aCallWithoutArgumentsSendsAnEmptyObjectAndAProseAnswerIsAString() {
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_get_server", "jira", args -> {
            calls.add(args);
            return textBlock("Jira 10.3, healthy");
        })));

        ScriptResult result = run(runner, "return mcp.call('jira_get_server');", ScriptMcpScope.CHAT);

        assertThat(result.value()).isEqualTo("Jira 10.3, healthy");
        assertThat(calls).containsExactly("{}");
    }

    @Test
    void theToolListHasOnlyWhatScriptAccessAllows() {
        ScriptRunner runner = runner(
                Map.of("jira", JIRA_READS),
                tools(
                        tool("jira_search", "jira", args -> "[]"),
                        tool("jira_create_issue", "jira", args -> "[]"),
                        tool("wiki_search", "wiki", args -> "[]")));

        ScriptResult result = run(runner, "return mcp.tools().map(t => t.name);", ScriptMcpScope.CHAT);

        assertThat(result.value()).isEqualTo(List.of("jira_search"));
    }

    @Test
    void aToolOutsideScriptAccessIsRefusedWithTheKeyThatDecides() {
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_create_issue", "jira", args -> {
            calls.add(args);
            return "[]";
        })));

        ScriptResult result = run(runner, "return mcp.call('jira_create_issue', {summary: 'x'});", ScriptMcpScope.CHAT);

        assertThat(calls).isEmpty();
        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.RUNTIME);
        assertThat(result.error().message()).contains("kb.mcp.script-access").contains("No MCP tool is");
    }

    @Test
    void aScriptCanCatchAFailedCallAndCarryOn() {
        ScriptRunner runner = runner(
                Map.of("jira", JIRA_READS),
                tools(new ScriptTool(
                        "jira_search",
                        "jira",
                        "search",
                        new UnavailableToolCallback(callback("jira_search", a -> "[]"), "jira"))));

        ScriptResult result = run(runner, """
                try { return mcp.call('jira_search', {}); }
                catch (e) { return 'fallback: ' + String(e.message).includes('not reachable'); }
                """, ScriptMcpScope.CHAT);

        assertThat(result.error()).isNull();
        assertThat(result.value()).isEqualTo("fallback: true");
        assertThat(result.log()).anyMatch(line -> line.contains("failed after"));
    }

    @Test
    void everyCallCountsAgainstTheBudgetARepeatIncluded() {
        AtomicInteger answered = new AtomicInteger();
        ScriptRunner runner = runner(
                new ScriptMcpProperties(true, 2, DataSize.ofMegabytes(16)),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", args -> {
                    answered.incrementAndGet();
                    return textBlock("{}");
                })));

        ScriptResult result =
                run(runner, "for (let i = 0; i < 3; i++) mcp.call('jira_search', {}); return 1;", ScriptMcpScope.CHAT);

        assertThat(answered).hasValue(2);
        assertThat(result.error()).isNotNull();
        assertThat(result.error().kind()).isEqualTo(ScriptError.Kind.BUDGET);
        assertThat(result.error().message()).contains("maxMcpCalls=2");
    }

    /** A refused call never reached a server, so the stats do not count it. */
    @Test
    void aCallRefusedByTheBudgetIsNotCounted() {
        ScriptRunner runner = runner(
                new ScriptMcpProperties(true, 2, DataSize.ofMegabytes(16)),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", args -> textBlock("{}"))));

        ScriptResult result = run(runner, """
                for (let i = 0; i < 5; i++) { try { mcp.call('jira_search', {}); } catch (e) {} }
                return 'done';
                """, ScriptMcpScope.CHAT);

        assertThat(result.value()).isEqualTo("done");
        assertThat(result.stats().mcpCalls()).isEqualTo(2);
    }

    /** The byte budget is bytes: a Cyrillic answer weighs twice its length. */
    @Test
    void theByteBudgetCountsUtf8Bytes() {
        ScriptRunner runner = runner(
                new ScriptMcpProperties(true, 20, DataSize.ofBytes(100)),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", args -> textBlock("я".repeat(60)))));

        ScriptResult result = run(runner, "return mcp.call('jira_search', {}).length;", ScriptMcpScope.CHAT);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("maxMcpBytes");
    }

    @Test
    void theAnswersOfARunAreHeldToTheByteBudget() {
        ScriptRunner runner = runner(
                new ScriptMcpProperties(true, 20, DataSize.ofBytes(100)),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", args -> textBlock("x".repeat(200)))));

        ScriptResult result = run(runner, "return mcp.call('jira_search', {}).length;", ScriptMcpScope.CHAT);

        assertThat(result.error()).isNotNull();
        assertThat(result.error().message()).contains("maxMcpBytes");
    }

    /** A script that caught the first refusal must not get to reach the server again. */
    @Test
    void aSpentByteBudgetStopsCallsBeforeTheyReachTheServer() {
        AtomicInteger answered = new AtomicInteger();
        ScriptRunner runner = runner(
                new ScriptMcpProperties(true, 20, DataSize.ofBytes(100)),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", args -> {
                    answered.incrementAndGet();
                    return textBlock("x".repeat(200));
                })));

        ScriptResult result = run(runner, """
                for (let i = 0; i < 5; i++) { try { mcp.call('jira_search', {}); } catch (e) {} }
                return 'done';
                """, ScriptMcpScope.CHAT);

        assertThat(answered).hasValue(1);
        assertThat(result.value()).isEqualTo("done");
    }

    /** Out of time mid-call is not an error a script may swallow and return a fallback over. */
    @Test
    void aRunOutOfTimeMidCallCannotBeCaughtIntoASuccess() {
        CountDownLatch never = new CountDownLatch(1);
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "[]";
        })));

        ScriptResult result = runner.run(
                request(
                        "try { return mcp.call('jira_search', {}); } catch (e) { return 'fallback'; }",
                        ScriptMcpScope.CHAT,
                        1),
                RunCancellation.none());
        never.countDown();

        assertThat(result.error()).isNotNull();
        assertThat(result.value()).isNotEqualTo("fallback");
        assertThat(result.error().message()).contains("waiting for an MCP tool");
    }

    /** A call refused because the run is already out of time never went out, so it is not counted. */
    @Test
    void aCallAfterTheDeadlineIsNeitherSentNorCounted() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            started.incrementAndGet();
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "[]";
        })));

        ScriptResult result = runner.run(
                request(
                        "for (let i = 0; i < 3; i++) { try { mcp.call('jira_search', {}); } catch (e) {} }"
                                + " return 'fallback';",
                        ScriptMcpScope.CHAT,
                        1),
                RunCancellation.none());
        never.countDown();

        assertThat(started).hasValue(1);
        assertThat(result.error()).isNotNull();
        assertThat(result.stats().mcpCalls()).isEqualTo(1);
    }

    /** A server that never answers must not keep the run past its own deadline. */
    @Test
    void aHangingToolDoesNotOutliveTheRunsTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "[]";
        })));

        long started = System.nanoTime();
        ScriptResult result = runner.run(
                request("return mcp.call('jira_search', {});", ScriptMcpScope.CHAT, 1), RunCancellation.none());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        never.countDown();

        assertThat(result.error()).isNotNull();
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void stoppingTheRunStopsWaitingForATool() {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch never = new CountDownLatch(1);
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            stop.set(true);
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "[]";
        })));

        long started = System.nanoTime();
        // A stopped run is thrown, not returned: nobody is listening for its result any more.
        assertThatThrownBy(() -> runner.run(
                        request("return mcp.call('jira_search', {});", ScriptMcpScope.CHAT, 30),
                        new RunCancellation(stop::get)))
                .isInstanceOf(ScriptCancelledException.class);
        never.countDown();

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000);
    }

    /** After Stop a script that swallowed the abandonment must not send the next request. */
    @Test
    void aStoppedRunSendsNoFurtherCallsEvenWhenTheScriptCatches() {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger started = new AtomicInteger();
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", args -> {
            started.incrementAndGet();
            stop.set(true);
            return "[]";
        })));

        assertThatThrownBy(() -> runner.run(
                        request(
                                "for (let i = 0; i < 3; i++) { try { mcp.call('jira_search', {}); } catch (e) {} }"
                                        + " return 'fallback';",
                                ScriptMcpScope.CHAT,
                                30),
                        new RunCancellation(stop::get)))
                .isInstanceOf(ScriptCancelledException.class);

        assertThat(started).hasValue(1);
    }

    @Test
    void noMcpObjectWithoutAChatScopeOrWithTheFeatureOff() {
        ScriptRunner noScope = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", a -> "[]")));
        ScriptRunner off = runner(
                ScriptFixtures.mcpDisabled(),
                Map.of("jira", JIRA_READS),
                tools(tool("jira_search", "jira", a -> "[]")));
        ScriptRunner noAccess = runner(Map.of(), tools(tool("jira_search", "jira", a -> "[]")));
        ScriptRunner noRegistry = runner(Map.of("jira", JIRA_READS), () -> null);

        assertThat(run(noScope, "return typeof mcp;", ScriptMcpScope.NONE).value())
                .isEqualTo("undefined");
        assertThat(run(off, "return typeof mcp;", ScriptMcpScope.CHAT).value()).isEqualTo("undefined");
        assertThat(run(noAccess, "return typeof mcp;", ScriptMcpScope.CHAT).value())
                .isEqualTo("undefined");
        assertThat(run(noRegistry, "return typeof mcp;", ScriptMcpScope.CHAT).value())
                .isEqualTo("undefined");
    }

    @Test
    void aScheduledRunSeesOnlyConnectionsThatAllowIt() {
        ScriptRunner runner = runner(
                Map.of("jira", JIRA_READS, "wiki", new ScriptAccess(List.of("*"), true)),
                tools(tool("jira_search", "jira", a -> "[]"), tool("wiki_search", "wiki", a -> "[]")));

        assertThat(run(runner, "return mcp.tools().map(t => t.name);", ScriptMcpScope.SCHEDULED)
                        .value())
                .isEqualTo(List.of("wiki_search"));
        assertThat(run(runner, "return mcp.tools().map(t => t.name);", ScriptMcpScope.CHAT)
                        .value())
                .isEqualTo(List.of("jira_search", "wiki_search"));
    }

    /** No connection open to scheduled runs — no object, not one that refuses every call. */
    @Test
    void aScheduledRunGetsNoMcpObjectWhenNoConnectionAllowsIt() {
        ScriptRunner runner = runner(Map.of("jira", JIRA_READS), tools(tool("jira_search", "jira", a -> "[]")));

        assertThat(run(runner, "return typeof mcp;", ScriptMcpScope.SCHEDULED).value())
                .isEqualTo("undefined");
        assertThat(run(runner, "return typeof mcp;", ScriptMcpScope.CHAT).value())
                .isEqualTo("object");
    }

    @Test
    void globsMatchWholeNamesAndTakeEverythingElseLiterally() {
        assertThat(ScriptMcp.glob("jira_get_*").matcher("jira_get_issue").matches())
                .isTrue();
        assertThat(ScriptMcp.glob("jira_get_*").matcher("xjira_get_issue").matches())
                .isFalse();
        assertThat(ScriptMcp.glob("jira.search").matcher("jiraXsearch").matches())
                .isFalse();
        assertThat(ScriptMcp.glob("jira_?et").matcher("jira_get").matches()).isTrue();
    }

    private ScriptRunner runner(Map<String, ScriptAccess> access, Supplier<@Nullable List<ScriptTool>> tools) {
        return runner(new ScriptMcpProperties(true, 20, DataSize.ofMegabytes(16)), access, tools);
    }

    private ScriptRunner runner(
            ScriptMcpProperties mcpProperties,
            Map<String, ScriptAccess> access,
            Supplier<@Nullable List<ScriptTool>> tools) {
        return new ScriptRunner(
                gitRegistry,
                null,
                properties,
                new ScriptEditPolicy(gitRegistry, properties),
                new InMemoryScriptResultStore(),
                new ScriptMcp(mcpProperties, access, tools));
    }

    private static Supplier<@Nullable List<ScriptTool>> tools(ScriptTool... tools) {
        List<ScriptTool> list = List.of(tools);
        return () -> list;
    }

    private ScriptResult run(ScriptRunner runner, String script, ScriptMcpScope scope) {
        return runner.run(request(script, scope, null), RunCancellation.none());
    }

    private static ScriptRequest request(String script, ScriptMcpScope scope, @Nullable Integer timeoutSeconds) {
        return new ScriptRequest(ScriptSource.inline(script), ScriptArgs.none(), timeoutSeconds, true, null, null, null)
                .withMcp(scope);
    }

    private static ScriptTool tool(String name, String connection, Function<String, String> answer) {
        return new ScriptTool(name, connection, name, callback(name, answer));
    }

    private static ToolCallback callback(String name, Function<String, String> answer) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return answer.apply(toolInput);
            }

            @Override
            public String call(String toolInput, @Nullable ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    /** The answer as Spring AI's MCP callback writes it. */
    private static String textBlock(String text) {
        return "[{\"type\":\"text\",\"text\":" + quote(text) + "}]";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void runGit(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
