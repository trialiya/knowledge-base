package io.github.trialiya.kb.model.attachment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import io.github.trialiya.kb.model.attachment.entity.AttachmentOwnerType;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolJson;
import io.github.trialiya.kb.tools.Compact;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Read-only DTO returned by the REST API and AI tools.
 *
 * <p>{@code ownerType} and the dates are for the UI and are not shown to the model (see {@link
 * ToolJson}): the owner is already named by {@code documentId} / {@code conversationId}.
 *
 * @param id attachment id
 * @param ownerType {@link AttachmentOwnerType#DOCUMENT} or {@link AttachmentOwnerType#CHAT}
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
        @JsonView(ToolJson.UiOnly.class) AttachmentOwnerType ownerType,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long documentId,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String conversationId,
        String fileName,
        String contentType,
        long fileSize,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String summary,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String sourceUrl,
        @JsonView(ToolJson.UiOnly.class) OffsetDateTime createdAt,
        @JsonView(ToolJson.UiOnly.class) OffsetDateTime updatedAt)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        return Compact.tag("att:" + id)
                .add("file", fileName)
                .add("type", contentType)
                .add("size", fileSize)
                .add("owner", ownerType)
                .add("conversation", conversationId)
                .add("doc", documentId)
                .add("sum", Compact.truncate(summary, 50))
                .done();
    }
}
