package io.github.trialiya.kb.model.attachment.dto;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;
import java.time.OffsetDateTime;

/**
 * Read-only DTO returned by the REST API and AI tools.
 *
 * @param id attachment id
 * @param ownerType {@code "document"} or {@code "chat"}
 * @param documentId owning document id (null for chat attachments)
 * @param conversationId owning conversation id (null for document attachments)
 * @param fileName original file name
 * @param contentType MIME type
 * @param fileSize size in bytes
 * @param summary AI-generated summary (null until requested)
 * @param sourceUrl source url
 * @param createdAt upload timestamp
 * @param updatedAt last modification timestamp
 */
public record Attachment(
        Long id,
        String ownerType,
        Long documentId,
        String conversationId,
        String fileName,
        String contentType,
        long fileSize,
        String summary,
        String sourceUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        return Compact.tag("att:" + id)
                .add("file", fileName)
                .add("type", contentType)
                .add("size", fileSize)
                .add("owner", ownerType)
                .add("doc", documentId)
                .add("sum", Compact.truncate(summary, 50))
                .done();
    }
}
