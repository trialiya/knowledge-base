package io.github.trialiya.kb.model.embedding;

import java.time.OffsetDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** One row in the {@code embedding_tasks} outbox table. */
@Data
@NoArgsConstructor
@Table("embedding_tasks")
public class EmbeddingTaskEntity {

    @Id
    private Long id;

    /** {@code "document"} or {@code "attachment"}. */
    private String entityType;

    private Long entityId;

    /** {@code pending} → {@code processing} → {@code done} / {@code failed}. */
    private String status;

    private int attempts;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
