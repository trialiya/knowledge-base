package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.service.AttachmentService;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    "Получить список вложений (файлов) для чат-беседы по conversationId. "
                            + "Возвращает метаданные: имя файла, тип, размер, краткое описание (если есть).")
    public List<Attachment> getChatAttachments(
            @ToolParam(description = "ID беседы (conversationId)") String conversationId) {
        log.info("getChatAttachments called: conversationId={}", conversationId);
        return attachmentService.findByConversation(conversationId);
    }

    // ── Read content ──────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Получить полное текстовое содержимое вложения по его id. "
                            + "Используй когда нужно проанализировать или процитировать содержимое файла.")
    public String getAttachmentContent(
            @ToolParam(description = "ID вложения") String attachmentId) {
        log.info("getAttachmentContent called: attachmentId={}", attachmentId);
        String content = attachmentService.getContent(Long.parseLong(attachmentId));
        if (content == null) return "(пустое содержимое)";
        // Truncate for tool response to avoid flooding the context window
        if (content.length() > 15_000) {
            return content.substring(0, 15_000)
                    + "\n... (содержимое обрезано, всего символов: "
                    + content.length()
                    + ")";
        }
        return content;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Tool(
            description =
                    "Поиск по вложениям (файлам) в базе знаний: по имени файла, содержимому и описанию. "
                            + "Используй когда пользователь ищет информацию, которая может быть в прикреплённых файлах.")
    public List<Attachment> searchAttachments(
            @ToolParam(description = "Поисковый запрос") String query) {
        log.info("searchAttachments called: query='{}'", query);
        return attachmentService.search(query);
    }
}
