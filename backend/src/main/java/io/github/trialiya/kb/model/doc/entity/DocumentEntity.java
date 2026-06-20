package io.github.trialiya.kb.model.doc.entity;

import io.github.trialiya.kb.model.doc.DocumentType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("documents")
public class DocumentEntity {

    @Id private Long id;
    private String title;
    private DocumentType type;
    private Long parentId;
    private String description;
    private LocalDateTime updatedAt;

    /** Zero-based display order among siblings. */
    private int position;

    /**
     * System-protected nodes cannot be deleted or renamed. Content (description) and attachments
     * are still editable.
     */
    private boolean isSystem;

    /**
     * Optimistic locking counter. Spring Data JDBC increments this automatically on every {@code
     * save()} and injects it into the {@code WHERE} clause:
     *
     * <pre>
     * UPDATE documents SET ..., version = version + 1
     * WHERE id = ? AND version = ?
     * </pre>
     *
     * If the row was modified by a concurrent request between our read and write, the update
     * matches 0 rows and Spring throws {@link
     * org.springframework.dao.OptimisticLockingFailureException}, which the controller layer maps
     * to HTTP 409 Conflict.
     */
    @Version private int version;

    // ── Summary ───────────────────────────────────────────────────────────────

    /**
     * AI-generated summary of the document description. {@code null} until the user explicitly
     * triggers summarisation via {@code POST /api/documents/{id}/summarize}.
     */
    private String summary;

    /**
     * The value of {@link #descriptionVersion} at the time {@link #summary} was last generated.
     * {@code null} while {@link #summary} is {@code null}.
     *
     * <p>Stale check: {@code summarySourceVersion < descriptionVersion}.
     */
    private Integer summarySourceVersion;

    /**
     * Incremented <em>only</em> when {@link #description} actually changes. Intentionally separate
     * from {@link #version}, which also grows on rename / move / reorder and must not affect the
     * stale calculation.
     *
     * <p>Starts at 1 for all rows (DB default = 1).
     */
    private int descriptionVersion;

    public boolean isSummaryStale() {
        if (getSummary() == null || getSummarySourceVersion() == null) return false;
        return getSummarySourceVersion() < getDescriptionVersion();
    }
}
