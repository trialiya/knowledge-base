package io.github.trialiya.kb.model.doc.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Immutable snapshot of a {@link DocumentEntity} captured <em>before</em> each update.
 *
 * <p>The {@link #version} field mirrors the {@code documents.version} value <em>at the time the
 * snapshot was taken</em>, so the history for a document currently at version N will contain rows
 * with versions 1 … N−1.
 *
 * <p>Rows are never mutated after insertion; there is no {@code @Version} here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("document_history")
public class DocumentHistoryEntity {

    @Id @Nullable private Long id;

    private Long documentId;

    /**
     * The version of the document at the time this snapshot was recorded. Corresponds to {@link
     * DocumentEntity#getVersion()} before the increment.
     */
    private int version;

    private String title;
    private String type;
    private String description;
    private LocalDateTime updatedAt;
    private String summary;
    @Nullable private Integer summarySourceVersion;
    private int descriptionVersion;
}
