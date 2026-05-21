package io.github.trialiya.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.trialiya.kb.config.model.AtlassianConfiguration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Fetches Confluence page content via the Confluence REST API.
 *
 * <p>Supports Atlassian Cloud (REST API v2, {@code /wiki/spaces/…/pages/…}) and Server / Data
 * Centre (REST API v1, {@code viewpage.action?pageId=…}) URL formats. The returned {@link
 * ConfluencePage#content()} is the storage-format body with HTML tags stripped, suitable for
 * indexing in the knowledge base.
 */
@Slf4j
@Service
public class ConfluenceService {

    // -------------------------------------------------------------------------
    // REST API
    // -------------------------------------------------------------------------

    /** Retrieves the storage-format body and version metadata for a page by its numeric ID. */
    private static final String PAGE_API_PATH_TEMPLATE =
            "/rest/api/content/%s?expand=body.storage,version";

    // -------------------------------------------------------------------------
    // URL patterns for page-ID extraction
    // -------------------------------------------------------------------------

    /**
     * Matches the numeric page ID in Atlassian Cloud URLs. Example: {@code
     * /wiki/spaces/ENG/pages/123456/My-Page}
     */
    private static final Pattern CLOUD_PAGE_ID_PATTERN =
            Pattern.compile("/wiki/spaces/[^/]+/pages/(\\d+)");

    /**
     * Matches the numeric page ID in Confluence Server / Data Centre URLs. Example: {@code
     * /pages/viewpage.action?pageId=123456}
     */
    private static final Pattern SERVER_PAGE_ID_PATTERN = Pattern.compile("pageId=(\\d+)");

    /** A URL (or bare value) that is already a plain numeric page ID. */
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("^\\d+$");

    // -------------------------------------------------------------------------
    // HTML → text conversion patterns
    // -------------------------------------------------------------------------

    /** Block-level elements that should become blank lines in the plain-text output. */
    private static final Pattern HTML_PARAGRAPH_CLOSE =
            Pattern.compile("</p>", Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_DIV_CLOSE =
            Pattern.compile("</div>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LIST_ITEM_CLOSE =
            Pattern.compile("</li>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LINE_BREAK =
            Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_HEADING_OPEN =
            Pattern.compile("<h[1-6][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_HEADING_CLOSE =
            Pattern.compile("</h[1-6]>", Pattern.CASE_INSENSITIVE);

    /** Catches any remaining HTML tag after block elements have been converted. */
    private static final Pattern HTML_ANY_TAG = Pattern.compile("<[^>]+>");

    /** Three or more consecutive newlines collapsed to two (one blank line). */
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("\n{3,}");

    private final RestClient confluenceRestClient;

    public ConfluenceService(AtlassianConfiguration config) {
        this.confluenceRestClient =
                RestClient.builder()
                        .baseUrl(config.confluence().baseUrl())
                        .defaultHeaders(
                                headers -> headers.setBearerAuth(config.confluence().apiToken()))
                        .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * A Confluence page reduced to the fields needed by the knowledge base.
     *
     * @param pageId numeric Confluence page identifier
     * @param title page title
     * @param content plain-text representation of the page body (HTML stripped)
     */
    public record ConfluencePage(String pageId, String title, String content) {}

    /**
     * Parses a numeric Confluence page ID from a URL, or returns the value as-is if it is already a
     * plain numeric ID.
     *
     * <p>Supported formats:
     *
     * <ul>
     *   <li>Cloud: {@code https://acme.atlassian.net/wiki/spaces/ENG/pages/123456/Title}
     *   <li>Server/DC: {@code https://acme.example.com/pages/viewpage.action?pageId=123456}
     *   <li>Bare numeric ID: {@code "123456"}
     * </ul>
     *
     * @param confluenceUrlOrId a Confluence page URL or a bare numeric page ID
     * @return the extracted numeric page ID as a string
     * @throws IllegalArgumentException if the page ID cannot be determined
     */
    public String parsePageIdFromUrl(String confluenceUrlOrId) {
        if (confluenceUrlOrId == null || confluenceUrlOrId.isBlank()) {
            throw new IllegalArgumentException("Confluence URL or page ID must not be blank");
        }

        Matcher cloudMatcher = CLOUD_PAGE_ID_PATTERN.matcher(confluenceUrlOrId);
        if (cloudMatcher.find()) {
            return cloudMatcher.group(1);
        }

        Matcher serverMatcher = SERVER_PAGE_ID_PATTERN.matcher(confluenceUrlOrId);
        if (serverMatcher.find()) {
            return serverMatcher.group(1);
        }

        if (NUMERIC_ID_PATTERN.matcher(confluenceUrlOrId.trim()).matches()) {
            return confluenceUrlOrId.trim();
        }

        throw new IllegalArgumentException(
                "Cannot extract a Confluence page ID from: " + confluenceUrlOrId);
    }

    /**
     * Fetches a Confluence page by its numeric ID and converts it to a {@link ConfluencePage}.
     *
     * @param pageId numeric Confluence page identifier
     * @return populated {@link ConfluencePage}
     * @throws RuntimeException if the Confluence API returns an empty body
     */
    public ConfluencePage fetchPage(String pageId) {
        String requestUri = String.format(PAGE_API_PATH_TEMPLATE, pageId);
        log.info("Fetching Confluence page '{}' from '{}'", pageId, requestUri);

        ResponseEntity<JsonNode> response =
                confluenceRestClient
                        .get()
                        .uri(requestUri)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(JsonNode.class);

        JsonNode responseBody = response.getBody();
        if (responseBody == null) {
            throw new RuntimeException("Empty response from Confluence API for page: " + pageId);
        }

        return parsePageFromJson(responseBody, pageId);
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    /** Converts the raw Confluence REST response into a {@link ConfluencePage}. */
    private ConfluencePage parsePageFromJson(JsonNode pageJson, String pageId) {
        String title = pageJson.path("title").asText("Untitled");
        String storageHtml = pageJson.path("body").path("storage").path("value").asText("");
        String plainText = convertStorageHtmlToPlainText(storageHtml, title);

        return new ConfluencePage(pageId, title, plainText);
    }

    // -------------------------------------------------------------------------
    // HTML → plain-text conversion
    // -------------------------------------------------------------------------

    /**
     * Converts Confluence storage-format HTML to human-readable plain text.
     *
     * <p>This is intentionally lightweight: it maps block-level elements to newlines, strips
     * remaining tags, and decodes the most common HTML entities. For richer conversion (tables,
     * macros, etc.) consider a proper HTML-to-Markdown library such as flexmark.
     *
     * @param storageHtml the raw {@code body.storage.value} from the Confluence API
     * @param pageTitle used as the top-level Markdown heading
     * @return readable plain text with a {@code # Title} header
     */
    private String convertStorageHtmlToPlainText(String storageHtml, String pageTitle) {
        if (storageHtml == null || storageHtml.isBlank()) {
            return "";
        }

        String text = storageHtml;

        // --- Block-level elements → whitespace ---
        text = HTML_LINE_BREAK.matcher(text).replaceAll("\n");
        text = HTML_PARAGRAPH_CLOSE.matcher(text).replaceAll("\n\n");
        text = HTML_DIV_CLOSE.matcher(text).replaceAll("\n");
        text = HTML_LIST_ITEM_CLOSE.matcher(text).replaceAll("\n");
        text = HTML_HEADING_OPEN.matcher(text).replaceAll("\n## ");
        text = HTML_HEADING_CLOSE.matcher(text).replaceAll("\n");

        // --- Strip all remaining tags ---
        text = HTML_ANY_TAG.matcher(text).replaceAll("");

        // --- Decode common HTML entities ---
        text = decodeHtmlEntities(text);

        // --- Normalise whitespace ---
        text = EXCESSIVE_BLANK_LINES.matcher(text).replaceAll("\n\n").strip();

        return "# " + pageTitle + "\n\n" + text;
    }

    /**
     * Replaces the most common HTML character references with their literal equivalents. Covers
     * {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;}, and {@code &nbsp;}.
     */
    private String decodeHtmlEntities(String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&nbsp;", " ");
    }
}
