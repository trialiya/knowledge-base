package io.github.trialiya.kb.config.model;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for external MCP (Model Context Protocol) tool servers, bound from:
 *
 * <pre>
 * kb:
 *   mcp:
 *     enabled: true
 *     bearer-tokens:          # connection name -> Bearer token, for authenticated remote servers
 *       jira: ${JIRA_MCP_TOKEN}
 *     headers:                # connection name -> arbitrary extra headers, for servers that need
 *       jira:                 # more than a bearer token (e.g. a second auth/tenant header)
 *         X-Atlassian-Cloud-Id: ${JIRA_CLOUD_ID}
 * </pre>
 *
 * <p>{@code enabled} gates whether tools from connected MCP servers (see {@code
 * spring.ai.mcp.client.*}) are merged into the chat model's tool list — see {@code
 * ChatConfig.chatClient}. {@code bearerTokens} and {@code headers} keys match the connection names
 * under {@code spring.ai.mcp.client.sse.connections} / {@code .streamable-http.connections}; {@code
 * bearerTokens} is applied as an {@code Authorization: Bearer <token>} header and {@code headers}
 * lets a connection send any additional header(s) a server requires on top of (or instead of) that
 * — see {@code McpClientConfig}.
 */
@ConfigurationProperties(prefix = "kb.mcp")
public record McpProperties(
        boolean enabled,
        Map<String, String> bearerTokens,
        Map<String, Map<String, String>> headers) {

    public McpProperties {
        bearerTokens = bearerTokens == null ? Map.of() : bearerTokens;
        headers = headers == null ? Map.of() : headers;
    }
}
