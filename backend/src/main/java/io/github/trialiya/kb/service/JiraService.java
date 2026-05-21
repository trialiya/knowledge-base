package io.github.trialiya.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.trialiya.kb.config.model.AtlassianConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Fetches Jira issue data via the Jira REST API.
 *
 * <p>Supports both Atlassian Cloud (API v3, Atlassian Document Format descriptions) and Server /
 * Data Centre (API v2, plain-text or HTML descriptions). The caller receives a {@link JiraIssue}
 * whose {@code content} field is a Markdown table ready to be stored as a knowledge-base
 * attachment.
 */
@Slf4j
@Service
public class JiraService {

    /** REST API path for a single issue. {@code {issueKey}} must be replaced before use. */
    private static final String ISSUE_API_PATH = "/rest/api/latest/issue/";

    /** ADF node type whose {@code text} leaf nodes should be followed by a newline. */
    private static final String ADF_BLOCK_TYPE_PARAGRAPH = "paragraph";

    private static final String ADF_BLOCK_TYPE_HEADING = "heading";

    private final RestClient jiraRestClient;

    public JiraService(AtlassianConfiguration config) {
        this.jiraRestClient =
                RestClient.builder()
                        .baseUrl(config.jira().baseUrl())
                        .defaultHeaders(headers -> headers.setBearerAuth(config.jira().apiToken()))
                        .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * A Jira issue reduced to the fields needed by the knowledge base.
     *
     * @param key issue key, e.g. {@code PROJ-123}
     * @param summary one-line issue summary
     * @param content Markdown representation of the full issue (fields + description + comments)
     */
    public record JiraIssue(String key, String summary, String content) {}

    /**
     * Parses a Jira issue key from a browse URL or returns the value as-is if it already looks like
     * a key.
     *
     * <p>Supported URL formats:
     *
     * <ul>
     *   <li>{@code https://host/browse/PROJ-123}
     *   <li>{@code https://host/jira/browse/PROJ-123}
     * </ul>
     *
     * @param jiraUrlOrKey a full Jira browse URL or a bare issue key ({@code PROJ-123})
     * @return the extracted issue key
     * @throws IllegalArgumentException if the key cannot be determined
     */
    public String parseIssueKeyFromUrl(String jiraUrlOrKey) {
        if (jiraUrlOrKey == null || jiraUrlOrKey.isBlank()) {
            throw new IllegalArgumentException("Jira URL or issue key must not be blank");
        }

        // Strip query string and trailing slashes, then split on "/"
        String pathOnly = jiraUrlOrKey.split("\\?")[0].replaceAll("/+$", "");
        String[] segments = pathOnly.split("/");

        for (int i = 0; i < segments.length; i++) {
            if ("browse".equalsIgnoreCase(segments[i]) && i + 1 < segments.length) {
                return segments[i + 1];
            }
        }

        // Accept a bare key like "PROJ-123" directly
        if (pathOnly.matches("^[A-Z][A-Z0-9_]+-\\d+$")) {
            return pathOnly;
        }

        throw new IllegalArgumentException("Cannot extract a Jira issue key from: " + jiraUrlOrKey);
    }

    /**
     * Fetches a Jira issue by its key and converts it to a {@link JiraIssue}.
     *
     * @param issueKey a valid Jira issue key, e.g. {@code PROJ-123}
     * @return populated {@link JiraIssue}
     * @throws RuntimeException if the Jira API returns an empty body
     */
    public JiraIssue fetchIssue(String issueKey) {
        String requestUri = ISSUE_API_PATH + issueKey;
        log.info("Fetching Jira issue '{}' from '{}'", issueKey, requestUri);

        ResponseEntity<JsonNode> response =
                jiraRestClient
                        .get()
                        .uri(requestUri)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(JsonNode.class);

        JsonNode responseBody = response.getBody();
        if (responseBody == null) {
            throw new RuntimeException("Empty response from Jira API for issue: " + issueKey);
        }

        return parseIssueFromJson(responseBody, issueKey);
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    /** Converts the raw Jira REST response into a {@link JiraIssue}. */
    private JiraIssue parseIssueFromJson(JsonNode issueJson, String fallbackKey) {
        String resolvedKey = issueJson.path("key").asText(fallbackKey);
        JsonNode fields = issueJson.path("fields");

        String summary = fields.path("summary").asText("");
        String content = buildMarkdownContent(resolvedKey, summary, fields);

        return new JiraIssue(resolvedKey, summary, content);
    }

    /**
     * Builds the Markdown representation of a Jira issue, including a metadata table, description,
     * and any comments.
     */
    private String buildMarkdownContent(String issueKey, String summary, JsonNode fields) {
        String status = fields.path("status").path("name").asText("—");
        String assignee = fields.path("assignee").path("displayName").asText("Не назначен");
        String reporter = fields.path("reporter").path("displayName").asText("—");
        String priority = fields.path("priority").path("name").asText("—");
        String issueType = fields.path("issuetype").path("name").asText("—");
        String createdAt = fields.path("created").asText("");
        String updatedAt = fields.path("updated").asText("");
        String description = extractTextFromField(fields, "description");

        StringBuilder markdown = new StringBuilder();

        markdown.append("# ").append(issueKey).append(": ").append(summary).append("\n\n");
        markdown.append("| Поле | Значение |\n|---|---|\n");
        markdown.append("| Тип | ").append(issueType).append(" |\n");
        markdown.append("| Статус | ").append(status).append(" |\n");
        markdown.append("| Приоритет | ").append(priority).append(" |\n");
        markdown.append("| Исполнитель | ").append(assignee).append(" |\n");
        markdown.append("| Автор | ").append(reporter).append(" |\n");
        markdown.append("| Создан | ").append(createdAt).append(" |\n");
        markdown.append("| Обновлён | ").append(updatedAt).append(" |\n");
        markdown.append("\n## Описание\n\n").append(description);

        appendComments(markdown, fields);

        return markdown.toString();
    }

    /** Appends all issue comments to {@code markdown}, if any exist. */
    private void appendComments(StringBuilder markdown, JsonNode fields) {
        JsonNode comments = fields.path("comment").path("comments");
        if (!comments.isArray() || comments.isEmpty()) {
            return;
        }

        markdown.append("\n\n## Комментарии\n\n");
        for (JsonNode comment : comments) {
            String author = comment.path("author").path("displayName").asText("—");
            String createdAt = comment.path("created").asText("");
            String body = extractTextFromField(comment, "body");

            markdown.append("**").append(author).append("** (").append(createdAt).append("):\n");
            markdown.append(body).append("\n\n");
        }
    }

    // -------------------------------------------------------------------------
    // ADF / plain-text extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts plain text from a named field that may be either an ADF object (Jira Cloud v3) or a
     * plain-text / HTML string (Jira Server v2).
     */
    private String extractTextFromField(JsonNode parentNode, String fieldName) {
        JsonNode fieldNode = parentNode.path(fieldName);
        if (fieldNode.isObject()) {
            // Atlassian Document Format (Cloud REST API v3)
            return extractPlainTextFromAdf(fieldNode);
        }
        // Plain text or raw HTML (Server / DC REST API v2)
        return fieldNode.asText("");
    }

    /**
     * Recursively walks an ADF node tree and concatenates all {@code text} leaf values. Block-level
     * node types ({@value #ADF_BLOCK_TYPE_PARAGRAPH}, {@value #ADF_BLOCK_TYPE_HEADING}) are
     * followed by a newline to preserve readability.
     *
     * @param adfNode root or intermediate ADF node
     * @return plain-text representation of the subtree
     */
    private String extractPlainTextFromAdf(JsonNode adfNode) {
        if (adfNode == null || adfNode.isMissingNode() || adfNode.isNull()) {
            return "";
        }

        // Leaf: a node that directly carries text
        if (adfNode.has("text")) {
            return adfNode.path("text").asText("");
        }

        StringBuilder text = new StringBuilder();
        JsonNode children = adfNode.path("content");

        if (children.isArray()) {
            for (JsonNode child : children) {
                String childText = extractPlainTextFromAdf(child);
                if (!childText.isEmpty()) {
                    text.append(childText);

                    // Add a newline after block-level elements for readability
                    String nodeType = child.path("type").asText("");
                    if (ADF_BLOCK_TYPE_PARAGRAPH.equals(nodeType)
                            || ADF_BLOCK_TYPE_HEADING.equals(nodeType)) {
                        text.append("\n");
                    }
                }
            }
        }

        return text.toString();
    }
}
