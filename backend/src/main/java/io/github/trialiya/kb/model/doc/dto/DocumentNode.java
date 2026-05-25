package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Full document node returned by {@code GET /api/documents/{id}} and tree/children endpoints.
 *
 * <p>Carries summary metadata alongside the document so the UI can show a "summary may be stale"
 * badge without an extra round-trip.
 */
@Data
public class DocumentNode {
    private final String id;
    private final String title;
    private final String type;
    private final String parentId;
    private final String description;
    private final LocalDateTime updatedAt;
    private final List<DocumentNode> children;
    private final boolean hasChildren;

    /**
     * When true: the UI must hide delete/rename controls and the server will reject delete/rename
     * requests with 403.
     */
    private final boolean system;

    // ── Summary ───────────────────────────────────────────────────────────────

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
