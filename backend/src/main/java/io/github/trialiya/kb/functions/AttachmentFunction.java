package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireContent;
import static io.github.trialiya.kb.tools.ToolArgs.requireId;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.attachment.dto.AttachmentContext;
import io.github.trialiya.kb.service.chat.AttachmentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools for read-only access to attachments in the knowledge base.
 *
 * <p>Capabilities:
 *
 * <ul>
 *   <li>{@link #getDocumentAttachments} — list attachments of a document
 *   <li>{@link #getChatAttachments} — list attachments of a chat conversation
 *   <li>{@link #getAttachmentContent} — read the full text content of an attachment
 *   <li>{@link #searchAttachments} — keyword search across all attachments
 * </ul>
 */
@Slf4j
@AllArgsConstructor
public class AttachmentFunction {

    private final AttachmentService attachmentService;

    // ── List by owner ─────────────────────────────────────────────────────────

    @Tool(
            description =
                    "List attachments (files) for a document by id. Returns metadata: file name, type, size, description.",
            resultConverter = CompactToolResultConverter.class)
    public List<Attachment> getDocumentAttachments(
            @ToolParam(description = "Document ID.") Long documentId) {
        final long id = requireId(documentId, "documentId");
        log.info("getDocumentAttachments called: documentId={}", id);
        return attachmentService.findByDocument(id);
    }

    @Tool(
            description =
                    "List attachments (files) for the current chat conversation. Returns metadata: file name, type, size, description.",
            resultConverter = CompactToolResultConverter.class)
    public List<Attachment> getChatAttachments(ToolContext context) {
        final String conversationId = conversationId(context);
        log.info("getChatAttachments called: conversationId={}", conversationId);
        return attachmentService.findByConversation(conversationId);
    }

    // ── Read content ──────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Read full text content of an attachment by id. Use when you need to analyze or cite file content.",
            resultConverter = CompactToolResultConverter.class)
    public String getAttachmentContent(
            ToolContext context, @ToolParam(description = "Attachment ID.") Long attachmentId) {
        final long id = requireId(attachmentId, "attachmentId");
        log.info("[{}] getAttachmentContent called: attachmentId={}", conversationId(context), id);
        String content = attachmentService.getContent(id);
        if (content == null) return "(empty content)";
        return getTruncatedContent(content);
    }

    // ── Create attachment ──────────────────────────────────────────────────────────

    /**
     * Creates a new attachment in the current chat conversation from raw text content.
     *
     * @param fileName name of the attachment file (e.g. "report.md")
     * @param contentType MIME type (e.g. "text/markdown", "application/json"); null for text/plain
     * @param content the raw text content to store
     * @return id of the newly created attachment
     */
    @Tool(
            description = "Create a new attachment in the current chat conversation.",
            resultConverter = CompactToolResultConverter.class)
    public long createAttachment(
            ToolContext context,
            @ToolParam(description = "Attachment file name (e.g., 'report.md').") String fileName,
            @ToolParam(
                            description =
                                    "MIME type (e.g., 'text/markdown', 'application/json'). "
                                            + "Null for 'text/plain'.",
                            required = false)
                    @Nullable String contentType,
            @ToolParam(description = "Attachment content (text, markdown, JSON, etc.).")
                    String content) {
        requireText(fileName, "fileName");
        // An attachment nobody can read is not a lesser version of the one the model asked for, so
        // a missing content is an error — while an explicit "" stays a legitimate empty file.
        requireContent(content, "content");
        // contentType is left as-is when absent: AttachmentService already falls back to
        // text/plain, and duplicating that default here would give it two places to drift.
        String conversationId = conversationId(context);
        log.info("[{}] createAttachment called: fileName={}", conversationId, fileName);
        return attachmentService
                .createFromText(conversationId, fileName, contentType, content)
                .id();
    }

    @Tool(
            description = "Read full text content of attachments by file name.",
            resultConverter = CompactToolResultConverter.class)
    public List<AttachmentContext> getAttachmentContentByFileName(
            ToolContext context, @ToolParam(description = "File name.") String fileName) {
        requireText(fileName, "fileName");
        final String conversationId = conversationId(context);
        log.info(
                "[{}] getAttachmentContentByFileName called: fileName='{}'",
                conversationId,
                fileName);
        return attachmentService.getByFileName(conversationId, fileName).stream()
                .map(
                        attachment ->
                                new AttachmentContext(
                                        attachment.id(),
                                        attachment.fileName(),
                                        getTruncatedContent(
                                                attachmentService.getContent(attachment.id()))))
                .toList();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Search attachments by file name, content, and description. Use when the user seeks information that may be in attached files.",
            resultConverter = CompactToolResultConverter.class)
    public List<Attachment> searchAttachments(
            ToolContext context, @ToolParam(description = "Search query.") String query) {
        // Not defaulted to "": that matches every attachment, and a dump of the whole list is not
        // the search the model asked for. getChatAttachments is the tool for listing.
        requireText(query, "query");
        final String conversationId = conversationId(context);
        log.info("searchAttachments called: query='{}'", query);
        return attachmentService.search(conversationId, query);
    }

    private static @NonNull String getTruncatedContent(String content) {
        // Truncate for tool response to avoid flooding the context window
        if (content.length() > 15_000) {
            return content.substring(0, 15_000)
                    + "\n... (content truncated; total chars: "
                    + content.length()
                    + ")";
        }
        return content;
    }
}
