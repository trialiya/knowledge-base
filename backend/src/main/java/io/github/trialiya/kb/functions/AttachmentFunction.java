package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.service.AttachmentService;
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
                    "Получить список вложений (файлов) для документа по его id. "
                            + "Возвращает метаданные: имя файла, тип, размер, краткое описание (если есть).")
    public List<Attachment> getDocumentAttachments(
            @ToolParam(description = "ID документа") String documentId) {
        log.info("getDocumentAttachments called: documentId={}", documentId);
        return attachmentService.findByDocument(Long.parseLong(documentId));
    }

    @Tool(
            description =
                    "Получить список вложений (файлов) для чат-беседы. "
                            + "Возвращает метаданные: имя файла, тип, размер, краткое описание (если есть).")
    public List<Attachment> getChatAttachments(ToolContext context) {
        final String conversationId = conversationId(context);
        log.info("getChatAttachments called: conversationId={}", conversationId);
        return attachmentService.findByConversation(conversationId);
    }

    // ── Read content ──────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Получить полное текстовое содержимое вложения по его id. "
                            + "Используй когда нужно проанализировать или процитировать содержимое файла.")
    public String getAttachmentContent(
            ToolContext context, @ToolParam(description = "ID вложения") String attachmentId) {
        log.info(
                "[{}] getAttachmentContent called: attachmentId={}",
                conversationId(context),
                attachmentId);
        String content = attachmentService.getContent(Long.parseLong(attachmentId));
        if (content == null) return "(пустое содержимое)";
        return getTruncatedContent(content);
    }

    // ── Create attachment ──────────────────────────────────────────────────────────

    // ── Create attachment ──────────────────────────────────────────────────────────

    /**
     * Creates a new attachment in the current chat conversation from raw text content.
     *
     * @param fileName name of the attachment file (e.g. "report.md")
     * @param contentType MIME type (e.g. "text/markdown", "application/json")
     * @param content the raw text content to store
     * @return id of the newly created attachment
     */
    @Tool(description = "Создать новое вложение в текущем чате.")
    public long createAttachment(
            ToolContext context,
            @ToolParam(description = "Название вложения - файла") String fileName,
            @ToolParam(description = "MIME type") String contentType,
            @ToolParam(description = "Содержимое вложения (текст, markdown, json, etc). ")
                    String content) {
        String conversationId = conversationId(context);
        log.info("[{}] createAttachment called: fileName={}", conversationId, fileName);
        return attachmentService.createFromText(conversationId, fileName, contentType, content).id();
    }

    @Tool(
            description =
                    "Получить полное текстовое содержимое вложения по имени файла. "
                            + "Используй когда нужно проанализировать или процитировать содержимое файла.")
    public List<AttachmentContext> getAttachmentContentByFileName(
            ToolContext context, @ToolParam(description = "Имя файла") String fileName) {
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

    public record AttachmentContext(long id, String fileName, String content) {}

    // ── Search ────────────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Поиск по вложениям (файлам) в базе знаний: по имени файла, содержимому и описанию. "
                            + "Используй когда пользователь ищет информацию, которая может быть в прикреплённых файлах.")
    public List<Attachment> searchAttachments(
            ToolContext context, @ToolParam(description = "Поисковый запрос") String query) {
        final String conversationId = conversationId(context);
        log.info("searchAttachments called: query='{}'", query);
        return attachmentService.search(conversationId, query);
    }

    private static @NonNull String getTruncatedContent(String content) {
        // Truncate for tool response to avoid flooding the context window
        if (content.length() > 15_000) {
            return content.substring(0, 15_000)
                    + "\n... (содержимое обрезано, всего символов: "
                    + content.length()
                    + ")";
        }
        return content;
    }
}
