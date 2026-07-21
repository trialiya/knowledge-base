package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.McpProperties;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpRequest;
import java.util.Optional;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authenticates outbound MCP (Model Context Protocol) connections to remote SSE / streamable-HTTP
 * servers (e.g. a self-hosted Jira/Confluence MCP server reachable only with a Bearer token) — see
 * {@code kb.mcp.bearer-tokens} in application.yaml and {@code McpProperties}.
 *
 * <p>{@link McpClientCustomizer#customize} is called once per configured connection, keyed by its
 * name under {@code spring.ai.mcp.client.sse.connections} / {@code .streamable-http.connections}. A
 * connection with no matching entry in {@code kb.mcp.bearer-tokens} is left unauthenticated (fine
 * for stdio servers, or a remote server that doesn't require one).
 */
@Configuration
public class McpClientConfig {

    @Bean
    public McpClientCustomizer<HttpClientSseClientTransport.Builder> mcpSseBearerAuthCustomizer(
            McpProperties mcpProperties) {
        return (name, builder) ->
                bearerToken(mcpProperties, name)
                        .ifPresent(token -> builder.requestBuilder(authorizedRequest(token)));
    }

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>
            mcpStreamableHttpBearerAuthCustomizer(McpProperties mcpProperties) {
        return (name, builder) ->
                bearerToken(mcpProperties, name)
                        .ifPresent(token -> builder.requestBuilder(authorizedRequest(token)));
    }

    private static Optional<String> bearerToken(
            McpProperties mcpProperties, String connectionName) {
        return Optional.ofNullable(mcpProperties.bearerTokens().get(connectionName))
                .filter(token -> !token.isBlank());
    }

    private static HttpRequest.Builder authorizedRequest(String token) {
        return HttpRequest.newBuilder().header("Authorization", "Bearer " + token);
    }
}
