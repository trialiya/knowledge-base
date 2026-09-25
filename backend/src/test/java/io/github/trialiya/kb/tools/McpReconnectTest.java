package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.tools.McpToolRegistry.ConnectionStatus;
import io.github.trialiya.kb.tools.McpToolRegistry.Status;
import io.github.trialiya.kb.tools.McpToolRegistry.ToolSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * The same promises as {@code McpToolRegistryTest} — a server that was not there is picked up, one
 * that went away and came back is usable again — kept by a real {@link McpSyncClient} over
 * streamable HTTP against a real MCP server, not by a {@code ToolSource} stand-in.
 *
 * <p>What only this can show is the half the registry does not own: that the SDK client, built
 * unopened the way {@code spring.ai.mcp.client.initialized=false} leaves it, opens its session on
 * the first probe that reaches a server, retries after a probe that did not, and opens a fresh one
 * when the server it had a session with was restarted and no longer knows it. If any of that stops
 * holding in an SDK upgrade, the registry's retries keep running and keep failing.
 *
 * <p>Rounds are driven by hand ({@link #probeRound}) rather than by the schedule, so the test says
 * how many rounds a recovery takes — the number a deployment multiplies by {@code
 * kb.mcp.retry-interval-ms}.
 */
class McpReconnectTest {

    private static final String CLIENT_NAME = "spring-ai-mcp-client";
    private static final String CONNECTION = "jira";
    private static final String TOOL = "issue";

    @TempDir Path tomcatDir;

    private final AtomicInteger probes = new AtomicInteger();
    private int port;
    private McpSyncClient client;
    private McpToolRegistry registry;
    private @Nullable Tomcat server;
    private @Nullable McpSyncServer mcpServer;

    @BeforeEach
    void setUp() {
        port = freePort();
        client =
                McpClient.sync(
                                HttpClientStreamableHttpTransport.builder(
                                                "http://localhost:" + port)
                                        .endpoint("/mcp")
                                        .connectTimeout(Duration.ofSeconds(2))
                                        .build())
                        .clientInfo(
                                new McpSchema.Implementation(CLIENT_NAME + " - " + CONNECTION, "1"))
                        .requestTimeout(Duration.ofSeconds(5))
                        .initializationTimeout(Duration.ofSeconds(5))
                        .build();
        // The real per-connection source, counted: a failed probe leaves the status where it was
        // (DOWN stays DOWN), so the status alone cannot say that a round has finished.
        ToolSource source =
                McpToolRegistry.sources(List.of(client), List.of(), CLIENT_NAME, null, null, null)
                        .get(CONNECTION);
        registry =
                new McpToolRegistry(
                        Map.of(
                                CONNECTION,
                                () -> {
                                    try {
                                        return source.list();
                                    } finally {
                                        probes.incrementAndGet();
                                    }
                                }));
    }

    @AfterEach
    void tearDown() {
        stopServer();
        client.close();
    }

    @Test
    void aServerStartedAfterTheApplicationIsPickedUpByTheNextRound() {
        registry.connect();
        awaitProbes(1);
        assertThat(registry.statuses())
                .containsExactly(new ConnectionStatus(CONNECTION, Status.DOWN, 0));
        assertThat(registry.callbacks()).isEmpty();

        startServer();
        probeRound();

        assertThat(registry.statuses())
                .containsExactly(new ConnectionStatus(CONNECTION, Status.UP, 1));
        assertThat(callTool()).contains("answered");
    }

    /**
     * The client still holds the session id of the server that went away, and the restarted server
     * answers it with «unknown session» (404). The SDK drops the session on that answer but fails
     * the request that got it, so the first round after a restart still finds the connection down
     * and the fresh session is opened by the round after — a recovery of up to two intervals, and a
     * tool call landing in that gap is answered with an error. Two rounds is the bound pinned here:
     * a client that reconnects in one keeps this green.
     */
    @Test
    void aRestartedServerIsUsableAgainWithinTwoRounds() {
        startServer();
        registry.connect();
        awaitProbes(1);
        assertThat(callTool()).contains("answered");

        stopServer();
        probeRound();
        assertThat(registry.statuses())
                .containsExactly(new ConnectionStatus(CONNECTION, Status.DOWN, 1));
        assertThatThrownBy(this::callTool).isInstanceOf(ToolExecutionException.class);

        startServer();
        probeRound();
        if (registry.statuses().getFirst().status() != Status.UP) {
            probeRound();
        }

        assertThat(registry.statuses())
                .containsExactly(new ConnectionStatus(CONNECTION, Status.UP, 1));
        assertThat(callTool()).contains("answered");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** One scheduled round, run now, returning once its probe has answered or failed. */
    private void probeRound() {
        int before = probes.get();
        registry.refreshAll();
        awaitProbes(before + 1);
    }

    /**
     * {@code probes} counts inside the source, a moment before the registry publishes what the
     * probe found, so the wait is for the published status to settle as well.
     */
    private void awaitProbes(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> probes.get() >= count);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> registry.statuses().getFirst().status() != Status.PENDING);
    }

    private String callTool() {
        return registry.callbacks().getFirst().call("{}");
    }

    /**
     * The client needs the URL before there is a server behind it, so the port is picked here and
     * released at once: dynamic like every other port in the suite, taken again by {@link
     * #startServer} a moment later.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fresh server every time — nothing of a stopped one's sessions survives into the next. */
    private void startServer() {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        McpSyncServer mcp =
                McpServer.sync(transport)
                        .serverInfo("test-server", "1")
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .tools(
                                SyncToolSpecification.builder()
                                        .tool(
                                                McpSchema.Tool.builder()
                                                        .name(TOOL)
                                                        .description("Looks an issue up")
                                                        .inputSchema(Map.of("type", "object"))
                                                        .build())
                                        .callHandler(
                                                (exchange, request) ->
                                                        McpSchema.CallToolResult.builder()
                                                                .addTextContent("answered")
                                                                .build())
                                        .build())
                        .build();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(tomcatDir.toString());
        tomcat.setPort(port);
        // Tomcat creates no connector of its own: without this call it starts and listens nowhere.
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
        context.addServletMappingDecoded("/*", "mcp");
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            mcp.close();
            throw new IllegalStateException(e);
        }
        server = tomcat;
        mcpServer = mcp;
    }

    private void stopServer() {
        if (server == null) {
            return;
        }
        try {
            mcpServer.close();
            server.stop();
            server.destroy();
        } catch (LifecycleException e) {
            throw new IllegalStateException(e);
        } finally {
            server = null;
            mcpServer = null;
        }
    }
}
