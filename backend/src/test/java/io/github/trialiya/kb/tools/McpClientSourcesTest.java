package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.tools.McpToolRegistry.ToolSource;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import reactor.core.publisher.Mono;

/**
 * The one step {@code McpToolRegistryTest} cannot reach through {@link ToolSource}: turning the
 * clients the MCP autoconfiguration registered into the per-connection sources everything else
 * works on. What is pinned here is that the client type stops mattering at exactly this point —
 * {@code spring.ai.mcp.client.type} picks sync or async clients, and either kind arrives as a
 * source under the connection name the configuration spells.
 */
class McpClientSourcesTest {

    private static final String CLIENT_NAME = "spring-ai-mcp-client";

    @Test
    void anAsyncClientBecomesASourceUnderItsConnectionName() {
        McpAsyncClient client = asyncClient("jira", "issue");

        Map<String, ToolSource> sources =
                McpToolRegistry.sources(List.of(), List.of(client), CLIENT_NAME, null, null, null);

        assertThat(sources).containsOnlyKeys("jira");
        assertThat(sources.get("jira").list()).hasSize(1);
    }

    /** Both kinds at once cannot happen through the autoconfiguration, but neither is dropped. */
    @Test
    void syncAndAsyncClientsLandInTheSameMap() {
        Map<String, ToolSource> sources = McpToolRegistry.sources(
                List.of(syncClient("files")), List.of(asyncClient("jira", "issue")), CLIENT_NAME, null, null, null);

        assertThat(sources).containsOnlyKeys("files", "jira");
    }

    /**
     * A client whose name does not carry the {@code "<client> - "} prefix the autoconfiguration
     * builds is reported under the name it has: guessing here would be a connection the Settings
     * panel silently drops.
     */
    @Test
    void aClientNameWithoutThePrefixIsUsedAsIs() {
        McpAsyncClient client = mock(McpAsyncClient.class);
        when(client.getClientInfo())
                .thenReturn(McpSchema.Implementation.builder("custom-name", "1").build());
        when(client.getClientCapabilities())
                .thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.listTools()).thenReturn(Mono.just(listToolsResult("issue")));

        Map<String, ToolSource> sources =
                McpToolRegistry.sources(List.of(), List.of(client), CLIENT_NAME, null, null, null);

        assertThat(sources).containsOnlyKeys("custom-name");
    }

    /**
     * The starter's generator renames a tool it has not seen before but whose name is taken, and a
     * tool whose description changed is one it has not seen. The registry re-reads every interval,
     * so with that generator an edited description would rename the tool on the next probe.
     */
    @Test
    void aToolKeepsItsNameWhenItsDescriptionChanges() {
        McpAsyncClient client = asyncClient("jira", "issue");
        when(client.listTools())
                .thenReturn(
                        Mono.just(listToolsResult("issue", "Looks an issue up")),
                        Mono.just(listToolsResult("issue", "Looks an issue up, faster")));

        ToolSource source = McpToolRegistry.sources(
                        List.of(), List.of(client), CLIENT_NAME, null, new DefaultMcpToolNamePrefixGenerator(), null)
                .get("jira");

        assertThat(source.list().getFirst().getToolDefinition().name()).isEqualTo("issue");
        assertThat(source.list().getFirst().getToolDefinition().name()).isEqualTo("issue");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static McpAsyncClient asyncClient(String connection, String toolName) {
        McpAsyncClient client = mock(McpAsyncClient.class);
        when(client.getClientInfo()).thenReturn(implementation(connection));
        // The provider describes the connection before it names a tool, and that description is
        // asserted non-null inside Spring AI — an unstubbed mock fails with its message, not ours.
        when(client.getClientCapabilities())
                .thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.listTools()).thenReturn(Mono.just(listToolsResult(toolName)));
        return client;
    }

    private static McpSyncClient syncClient(String connection) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(implementation(connection));
        return client;
    }

    /** Named the way {@code McpClientAutoConfiguration} names a client of that connection. */
    private static McpSchema.Implementation implementation(String connection) {
        return McpSchema.Implementation.builder(CLIENT_NAME + " - " + connection, "1")
                .build();
    }

    private static McpSchema.ListToolsResult listToolsResult(String toolName) {
        return listToolsResult(toolName, toolName);
    }

    private static McpSchema.ListToolsResult listToolsResult(String toolName, String description) {
        return new McpSchema.ListToolsResult(
                List.of(McpSchema.Tool.builder(toolName, Map.of("type", "object"))
                        .description(description)
                        .build()),
                null,
                Map.of());
    }
}
