package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.McpProperties;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link McpClientConfig#authRequest} builds the request template every MCP connection is
 * authenticated with — a silent precedence flip or a swallowed startup error here breaks auth
 * without failing the wider test suite, so the rules from the class javadoc are pinned directly.
 */
class McpClientConfigTest {

    private static final String CONNECTION = "jira";

    @Test
    void noBearerTokenAndNoHeadersLeavesTheConnectionUnauthenticated() {
        McpProperties properties = properties(Map.of(), Map.of());

        assertThat(McpClientConfig.authRequest(properties, CONNECTION)).isEmpty();
    }

    @Test
    void bearerTokenAloneBecomesTheAuthorizationHeader() {
        McpProperties properties = properties(Map.of(CONNECTION, "secret-token"), Map.of());

        assertThat(headerOf(properties, "Authorization")).contains("Bearer secret-token");
    }

    @Test
    void aHeaderNamedAuthorizationOverridesTheBearerToken() {
        McpProperties properties =
                properties(
                        Map.of(CONNECTION, "bearer-token"),
                        Map.of(CONNECTION, Map.of("Authorization", "Custom scheme-value")));

        assertThat(headerOf(properties, "Authorization")).contains("Custom scheme-value");
    }

    @Test
    void aBlankBearerTokenIsTreatedAsNotConfigured() {
        McpProperties properties =
                properties(
                        Map.of(CONNECTION, "  "),
                        Map.of(CONNECTION, Map.of("X-Atlassian-Cloud-Id", "cloud-id")));

        assertThat(headerOf(properties, "Authorization")).isEmpty();
        assertThat(headerOf(properties, "X-Atlassian-Cloud-Id")).contains("cloud-id");
    }

    @Test
    void aBlankCustomHeaderValueIsOmittedRatherThanSentEmpty() {
        McpProperties properties =
                properties(Map.of(), Map.of(CONNECTION, Map.of("X-Atlassian-Cloud-Id", "")));

        assertThat(McpClientConfig.authRequest(properties, CONNECTION)).isEmpty();
    }

    @Test
    void aRestrictedHeaderNameFailsWithAConfigErrorNamingTheConnectionAndHeader() {
        McpProperties properties = properties(Map.of(), Map.of(CONNECTION, Map.of("Host", "evil")));

        assertThatThrownBy(() -> McpClientConfig.authRequest(properties, CONNECTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kb.mcp.headers." + CONNECTION)
                .hasMessageContaining("Host")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private static Optional<String> headerOf(McpProperties properties, String headerName) {
        return McpClientConfig.authRequest(properties, CONNECTION)
                .map(builder -> builder.uri(URI.create("https://example.invalid")).build())
                .map(HttpRequest::headers)
                .map(headers -> headers.firstValue(headerName))
                .orElseGet(Optional::empty);
    }

    private static McpProperties properties(
            Map<String, String> bearerTokens, Map<String, Map<String, String>> headers) {
        return new McpProperties(true, bearerTokens, headers);
    }
}
