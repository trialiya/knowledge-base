package io.github.trialiya.kb.model.doc.entity;

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
    private String type;
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
}
