package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.McpProperties;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authenticates outbound MCP (Model Context Protocol) connections to remote SSE / streamable-HTTP
 * servers (e.g. a self-hosted Jira/Confluence MCP server reachable only with a Bearer token, or one
 * that also needs a tenant/cloud-id header alongside it) — see {@code kb.mcp.bearer-tokens} and
 * {@code kb.mcp.headers} in application.yaml and {@code McpProperties}.
 *
 * <p>{@link McpClientCustomizer#customize} is called once per configured connection, keyed by its
 * name under {@code spring.ai.mcp.client.sse.connections} / {@code .streamable-http.connections}. A
 * connection with no matching entry in {@code kb.mcp.bearer-tokens} or {@code kb.mcp.headers} is
 * left unauthenticated (fine for stdio servers, or a remote server that doesn't require one).
 */
@Configuration
public class McpClientConfig {

    // HttpClientSseClientTransport is deprecated in favor of the Streamable HTTP transport (MCP
    // spec 2025-03-26), but legacy HTTP+SSE servers configured under
    // spring.ai.mcp.client.sse.connections still need this customizer — there is no
    // Streamable-HTTP-only replacement that keeps them working.
    @Bean
    @SuppressWarnings("deprecation")
    public McpClientCustomizer<HttpClientSseClientTransport.Builder> mcpSseBearerAuthCustomizer(
            McpProperties mcpProperties) {
        return (name, builder) ->
                authRequest(mcpProperties, name).ifPresent(builder::requestBuilder);
    }

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>
            mcpStreamableHttpBearerAuthCustomizer(McpProperties mcpProperties) {
        return (name, builder) ->
                authRequest(mcpProperties, name).ifPresent(builder::requestBuilder);
    }

    /**
     * Builds the request template for a connection out of its bearer token (if any) and its custom
     * headers (if any) — e.g. the {@code X-Atlassian-Cloud-Id} header some Atlassian MCP
     * deployments require alongside {@code Authorization}. Uses {@code setHeader} rather than
     * {@code header} so a {@code kb.mcp.headers} entry named {@code Authorization} replaces rather
     * than duplicates the bearer-token header. Empty when the connection has neither configured.
     */
    static Optional<HttpRequest.Builder> authRequest(
            McpProperties mcpProperties, String connectionName) {
        Optional<String> token = bearerToken(mcpProperties, connectionName);
        Map<String, String> headers = customHeaders(mcpProperties, connectionName);
        if (token.isEmpty() && headers.isEmpty()) {
            return Optional.empty();
        }
        HttpRequest.Builder request = HttpRequest.newBuilder();
        token.ifPresent(value -> request.setHeader("Authorization", "Bearer " + value));
        headers.forEach(
                (headerName, headerValue) -> {
                    try {
                        request.setHeader(headerName, headerValue);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(
                                "kb.mcp.headers.%s has an invalid header '%s': %s"
                                        .formatted(connectionName, headerName, e.getMessage()),
                                e);
                    }
                });
        return Optional.of(request);
    }

    private static Optional<String> bearerToken(
            McpProperties mcpProperties, String connectionName) {
        return Optional.ofNullable(mcpProperties.bearerTokens().get(connectionName))
                .filter(token -> !token.isBlank());
    }

    /**
     * Custom headers of one connection, with the unconfigured ones dropped. A key written in YAML
     * with nothing after the colon binds as {@code null}, not as an empty string — both the
     * connection's whole header map ({@code headers.jira:}) and a single header ({@code
     * X-Atlassian-Cloud-Id:}, e.g. an environment variable nobody set in this deployment). Such a
     * header is simply not sent, the same answer {@link #bearerToken} gives; without the null
     * checks it would be an NPE while building the customizer, i.e. a failed startup over a header
     * the deployment does not use.
     */
    private static Map<String, String> customHeaders(
            McpProperties mcpProperties, String connectionName) {
        Map<String, String> configured = mcpProperties.headers().get(connectionName);
        if (configured == null) {
            return Map.of();
        }
        return configured.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
