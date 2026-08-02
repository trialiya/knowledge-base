package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.attachment.dto.AttachmentContext;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
            @ToolParam(description = "Document ID.") String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        log.info("getDocumentAttachments called: documentId={}", documentId);
        return attachmentService.findByDocument(Long.parseLong(documentId));
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
            ToolContext context, @ToolParam(description = "Attachment ID.") String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new IllegalArgumentException("attachmentId is required");
        }
        log.info(
                "[{}] getAttachmentContent called: attachmentId={}",
                conversationId(context),
                attachmentId);
        String content = attachmentService.getContent(Long.parseLong(attachmentId));
        if (content == null) return "(empty content)";
        return getTruncatedContent(content);
    }

    // ── Create attachment ──────────────────────────────────────────────────────────

    /**
     * Creates a new attachment in the current chat conversation from raw text content.
     *
     * @param fileName name of the attachment file (e.g. "report.md")
     * @param contentType MIME type (e.g. "text/markdown", "application/json")
     * @param content the raw text content to store
     * @return id of the newly created attachment
     */
    @Tool(
            description = "Create a new attachment in the current chat conversation.",
            resultConverter = CompactToolResultConverter.class)
    public long createAttachment(
            ToolContext context,
            @ToolParam(description = "Attachment file name (e.g., 'report.md').") String fileName,
            @ToolParam(description = "MIME type (e.g., 'text/markdown', 'application/json').")
                    String contentType,
            @ToolParam(description = "Attachment content (text, markdown, JSON, etc.).")
                    String content) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = "text/plain";
        }
        if (content == null) {
            content = "";
        }
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
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
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
        if (query == null) {
            query = "";
        }
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
