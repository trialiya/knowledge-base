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
 * </pre>
 *
 * <p>{@code enabled} gates whether tools from connected MCP servers (see {@code
 * spring.ai.mcp.client.*}) are merged into the chat model's tool list — see {@code
 * ChatConfig.chatClientBuilder}. {@code bearerTokens} keys match the connection names under {@code
 * spring.ai.mcp.client.sse.connections} / {@code .streamable-http.connections} and are applied as
 * an {@code Authorization: Bearer <token>} header — see {@code McpClientConfig}.
 */
@ConfigurationProperties(prefix = "kb.mcp")
public record McpProperties(boolean enabled, Map<String, String> bearerTokens) {

    public McpProperties {
        bearerTokens = bearerTokens == null ? Map.of() : bearerTokens;
    }
}
