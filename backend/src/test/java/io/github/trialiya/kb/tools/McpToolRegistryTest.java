package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.tools.McpToolRegistry.ConnectionStatus;
import io.github.trialiya.kb.tools.McpToolRegistry.Status;
import io.github.trialiya.kb.tools.McpToolRegistry.ToolSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * The failure the registry exists for: an MCP server that is not there. What is pinned here is the
 * behaviour the application's startup now depends on — a connection fails alone, the others keep
 * their tools, and a server that comes up later is picked up without a restart.
 *
 * <p>Connections are stood in for by {@link ToolSource}s, because the states that matter (throws,
 * answers, throws again, answers again) are exactly what a real server is unwilling to produce on
 * request.
 *
 * <p>What a failure does to the tool <em>set</em> is pinned here too: nothing. A connection going
 * down leaves its tools in place answering with an error, and only a successful probe rewrites the
 * list — see {@link UnavailableToolCallback} for the prompt-cache reason.
 */
class McpToolRegistryTest {

    @Test
    void aConnectionThatFailsCostsOnlyItsOwnTools() {
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "up",
                                () -> List.of(tool("search")),
                                "down",
                                McpToolRegistryTest::unreachable));

        registry.connect();
        awaitProbed(registry);

        assertThat(names(registry)).containsExactly("search");
        assertThat(registry.statuses())
                .containsExactly(
                        new ConnectionStatus("up", Status.UP, 1),
                        new ConnectionStatus("down", Status.DOWN, 0));
    }

    /** Nothing is read off a server while the context is coming up — that is the whole point. */
    @Test
    void nothingIsProbedBeforeTheApplicationIsUp() {
        AtomicInteger probes = new AtomicInteger();
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "jira",
                                () -> {
                                    probes.incrementAndGet();
                                    return List.of(tool("issue"));
                                }));

        assertThat(probes).hasValue(0);
        assertThat(registry.callbacks()).isEmpty();
        assertThat(registry.statuses())
                .containsExactly(new ConnectionStatus("jira", Status.PENDING, 0));
    }

    /** A server that was down at startup and came up later, without a restart of this process. */
    @Test
    void aConnectionThatComesUpLaterIsPickedUpByTheRetry() {
        AtomicReference<List<ToolCallback>> answer = new AtomicReference<>(null);
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "jira",
                                () -> {
                                    List<ToolCallback> tools = answer.get();
                                    if (tools == null) {
                                        return unreachable();
                                    }
                                    return tools;
                                }));

        registry.connect();
        awaitProbed(registry);
        assertThat(registry.callbacks()).isEmpty();

        answer.set(List.of(tool("issue")));
        registry.refreshAll();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(names(registry)).containsExactly("issue"));
    }

    /**
     * A server that dies after a successful probe: nothing announces that, so the scheduled round
     * is the only thing that can notice. What it must NOT do is take the tools out of the set — the
     * tool list is the prompt prefix every provider caches by, so a blinking server would cost
     * every conversation its cache twice.
     */
    @Test
    void toolsOfAConnectionThatDiedStayInTheSetAndAnswerWithAnError() {
        AtomicReference<Boolean> reachable = new AtomicReference<>(true);
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "jira",
                                () -> reachable.get() ? List.of(tool("issue")) : unreachable()));

        registry.connect();
        awaitProbed(registry);
        assertThat(names(registry)).containsExactly("issue");

        reachable.set(false);
        registry.refreshAll();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(registry.statuses())
                                        .containsExactly(
                                                new ConnectionStatus("jira", Status.DOWN, 1)));
        assertThat(names(registry)).containsExactly("issue");
        assertThatThrownBy(() -> registry.callbacks().getFirst().call("{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("'jira'")
                .hasMessageContaining("not reachable");
    }

    /** The tools come back the moment the server answers again — no wrapper left behind. */
    @Test
    void aToolIsCallableAgainOnceItsConnectionIsBack() {
        AtomicReference<Boolean> reachable = new AtomicReference<>(false);
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "jira",
                                () -> reachable.get() ? List.of(tool("issue")) : unreachable()));

        registry.connect();
        awaitProbed(registry);
        reachable.set(true);
        registry.refreshAll();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(names(registry)).containsExactly("issue"));
        assertThat(registry.callbacks().getFirst().call("{}")).isEqualTo("{}");
    }

    /**
     * Unavailability is not deletion, but a server that answers <em>is</em> allowed to say a tool
     * is gone: only a successful probe rewrites the list.
     */
    @Test
    void aToolTheServerStoppedAdvertisingIsDropped() {
        AtomicReference<List<ToolCallback>> advertised =
                new AtomicReference<>(List.of(tool("issue"), tool("search")));
        McpToolRegistry registry = new McpToolRegistry(sources("jira", advertised::get));

        registry.connect();
        awaitProbed(registry);
        assertThat(names(registry)).containsExactly("issue", "search");

        advertised.set(List.of(tool("issue")));
        registry.onToolsChanged(
                new org.springframework.ai.mcp.McpToolsChangedEvent("jira", List.of()));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(names(registry)).containsExactly("issue"));
    }

    /**
     * A round is as slow as its slowest connection — a server that answers nothing holds its probe
     * until the request times out. What must not happen is that it holds everyone else's tools with
     * it: connections probed before it are already offered to the model.
     */
    @Test
    void aSlowConnectionDoesNotHoldBackTheOnesAlreadyProbed() throws Exception {
        CountDownLatch answering = new CountDownLatch(1);
        McpToolRegistry registry =
                new McpToolRegistry(
                        sources(
                                "fast",
                                () -> List.of(tool("search")),
                                "slow",
                                () -> {
                                    await(answering);
                                    return List.of(tool("issue"));
                                }));

        registry.connect();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(names(registry)).containsExactly("search"));
        assertThat(registry.statuses())
                .containsExactly(
                        new ConnectionStatus("fast", Status.UP, 1),
                        new ConnectionStatus("slow", Status.PENDING, 0));

        answering.countDown();
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(names(registry)).containsExactly("search", "issue"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never let this probe answer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(McpToolRegistry registry) {
        return registry.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** The registry probes in the background; a probe is done once no connection is PENDING. */
    private static void awaitProbed(McpToolRegistry registry) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                registry.statuses().stream()
                                        .noneMatch(status -> status.status() == Status.PENDING));
    }

    private static List<ToolCallback> unreachable() {
        throw new IllegalStateException("connection refused");
    }

    private static Map<String, ToolSource> sources(String name, ToolSource source) {
        return Map.of(name, source);
    }

    private static Map<String, ToolSource> sources(
            String first, ToolSource firstSource, String second, ToolSource secondSource) {
        Map<String, ToolSource> sources = new LinkedHashMap<>();
        sources.put(first, firstSource);
        sources.put(second, secondSource);
        return sources;
    }

    private static ToolCallback tool(String name) {
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
                return "{}";
            }

            @Override
            public String call(
                    String toolInput, @org.jspecify.annotations.Nullable ToolContext ctx) {
                return call(toolInput);
            }
        };
    }
}
