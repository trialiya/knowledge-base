package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.tools.Compact;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight document DTO returned by create / update / move operations.
 *
 * <p>Note: {@code description} is intentionally {@code null} here to keep mutation-response
 * payloads small — fetch the full document via {@code GET /api/documents/{id}} to get description
 * and children.
 *
 * <p>Summary fields are always populated so the UI can reflect stale state after a save without
 * requiring a separate GET.
 *
 * @param summary AI-generated summary, or {@code null} if never summarised.
 * @param summaryStale {@code true} when the description has changed since the last summarisation,
 *     i.e. the summary may no longer reflect the current content. Always {@code false} when {@link
 *     #summary} is {@code null} (nothing to be stale yet).
 * @param summarySourceVersion The {@code descriptionVersion} at which the summary was generated.
 *     {@code null} while {@link #summary} is {@code null}.
 */
public record Document(
        long id,
        String title,
        String type,
        Long parentId,
        int version,
        int descriptionVersion,
        String description,
        LocalDateTime updatedAt,
        List<Document> children,
        String summary,
        boolean summaryStale,
        Integer summarySourceVersion)
        implements ToolCallResponseItem {
    @Override
    public String getFormattedResponse() {
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("type", type)
                .add("parent", parentId)
                .add("version", version)
                .add("updated", updatedAt)
                .body(Compact.truncate(description, 50))
                .done();
    }

    public DocumentShort toDocumentShort() {
        return new DocumentShort(
                id,
                title,
                type,
                parentId,
                version,
                descriptionVersion,
                updatedAt,
                summaryStale,
                summarySourceVersion);
    }
}
