package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds Atlassian service credentials from the {@code kb.atlassian} configuration prefix.
 *
 * <p>Expected YAML structure:
 *
 * <pre>
 * kb:
 *   atlassian:
 *     jira:
 *       base-url: https://your-instance.atlassian.net
 *       api-token: ATATT...
 *     confluence:
 *       base-url: https://your-instance.atlassian.net
 *       api-token: ATATT...
 * </pre>
 */
@ConfigurationProperties(prefix = "kb.atlassian")
public record AtlassianConfiguration(ServiceCredentials jira, ServiceCredentials confluence) {

    /**
     * Holds the base URL and bearer API token for a single Atlassian service (Jira or Confluence).
     *
     * @param baseUrl root URL of the Atlassian instance, e.g. {@code https://acme.atlassian.net}
     * @param apiToken personal access token (PAT) used as a Bearer credential
     */
    public record ServiceCredentials(String baseUrl, String apiToken) {}
}
