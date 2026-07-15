package io.github.trialiya.kb.model.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** One row in the {@code embedding_tasks} outbox table. */
@Data
@NoArgsConstructor
@Table("embedding_tasks")
public class EmbeddingTaskEntity {

    @Id private Long id;

    private EmbeddingEntityType entityType;

    private Long entityId;

    /**
     * Status machine: {@code pending → starting → done / failed / pending (retry) / superseded}.
     * {@code superseded} means the task was collapsed into a newer {@code pending} task for the
     * same entity, enqueued while this one was in flight — the newer task re-embeds the entity, so
     * nothing is lost.
     */
    private EmbeddingTaskStatus status;

    private int attempts;

    /**
     * Set by {@link io.github.trialiya.kb.repository.EmbeddingTaskRepository#claimPending} to the
     * batch's random UUID. Workers validate this token before marking a task done/failed so that a
     * stale worker cannot overwrite results claimed by the stuck-task reaper.
     */
    private UUID claimToken;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
