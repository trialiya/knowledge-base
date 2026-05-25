package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Lightweight document DTO returned by create / update / move operations.
 *
 * <p>Note: {@code description} is intentionally {@code null} here to keep mutation-response
 * payloads small — fetch the full document via {@code GET /api/documents/{id}} to get description
 * and children.
 *
 * <p>Summary fields are always populated so the UI can reflect stale state after a save without
 * requiring a separate GET.
 */
@Data
public class Document {
    private final String id;
    private final String title;
    private final String type;
    private final String parentId;
    private final String description;
    private final LocalDateTime updatedAt;
    private final List<Document> children;

    /** AI-generated summary, or {@code null} if never summarised. */
    private final String summary;

    /**
     * {@code true} when the description has changed since the last summarisation, i.e. the summary
     * may no longer reflect the current content. Always {@code false} when {@link #summary} is
     * {@code null} (nothing to be stale yet).
     */
    private final boolean summaryStale;

    /**
     * The {@code descriptionVersion} at which the summary was generated. {@code null} while {@link
     * #summary} is {@code null}.
     */
    private final Integer summarySourceVersion;
}
