package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public repository for {@link EmbeddingTaskEntity}.
 *
 * <p>Delegates simple operations to {@link EmbeddingTaskCrudRepository}. Uses {@link JdbcTemplate}
 * for {@link #claimPending} because Spring Data JDBC cannot map {@code UPDATE … RETURNING} results
 * via {@code @Query}.
 */
@Repository
@RequiredArgsConstructor
public class EmbeddingTaskRepository {

    private final EmbeddingTaskCrudRepository crud;
    private final JdbcTemplate jdbc;

    /**
     * Atomically claims up to {@code batchSize} pending tasks: marks them {@code processing},
     * increments {@code attempts}, and returns the claimed rows.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} ensures multiple concurrent callers never grab the same
     * rows.
     */
    @Transactional
    public List<EmbeddingTaskEntity> claimPending(int batchSize) {
        return jdbc.query(
                """
                UPDATE embedding_tasks
                   SET status = 'processing', updated_at = NOW(), attempts = attempts + 1
                 WHERE id IN (
                       SELECT id FROM embedding_tasks
                        WHERE status = 'pending'
                        ORDER BY created_at
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                       )
                RETURNING id, entity_type, entity_id, status, attempts, created_at, updated_at
                """,
                TASK_ROW_MAPPER,
                batchSize);
    }

    public void markDone(Long id) {
        crud.markDone(id);
    }

    public void markFailed(Long id) {
        crud.markFailed(id);
    }

    /** Enqueue only when no active task for this entity already exists. */
    public void enqueueIfAbsent(String entityType, Long entityId) {
        crud.enqueueIfAbsent(entityType, entityId);
    }

    /** Unconditional enqueue — for reindexAll where re-processing is intentional. */
    public void enqueue(String entityType, Long entityId) {
        crud.enqueue(entityType, entityId);
    }

    private static final RowMapper<EmbeddingTaskEntity> TASK_ROW_MAPPER =
            (rs, rowNum) -> {
                EmbeddingTaskEntity e = new EmbeddingTaskEntity();
                e.setId(rs.getLong("id"));
                e.setEntityType(rs.getString("entity_type"));
                e.setEntityId(rs.getLong("entity_id"));
                e.setStatus(rs.getString("status"));
                e.setAttempts(rs.getInt("attempts"));
                Timestamp created = rs.getTimestamp("created_at");
                if (created != null) {
                    e.setCreatedAt(created.toInstant().atOffset(ZoneOffset.UTC));
                }
                Timestamp updated = rs.getTimestamp("updated_at");
                if (updated != null) {
                    e.setUpdatedAt(updated.toInstant().atOffset(ZoneOffset.UTC));
                }
                return e;
            };
}
