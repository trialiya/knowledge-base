package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** Spring Data JDBC CRUD for {@link EmbeddingTaskEntity}. Used internally by {@link EmbeddingTaskRepository}. */
interface EmbeddingTaskCrudRepository extends CrudRepository<EmbeddingTaskEntity, Long> {

    @Modifying
    @Query("UPDATE embedding_tasks SET status = 'done', updated_at = NOW() WHERE id = :id")
    void markDone(@Param("id") Long id);

    @Modifying
    @Query("UPDATE embedding_tasks SET status = 'failed', updated_at = NOW() WHERE id = :id")
    void markFailed(@Param("id") Long id);

    /**
     * Inserts a pending task only when no active (pending/processing) task exists for the same
     * entity. Prevents double-queueing on rapid document updates.
     */
    @Modifying
    @Query(
            """
            INSERT INTO embedding_tasks (entity_type, entity_id, status, attempts, created_at, updated_at)
            SELECT :entityType, :entityId, 'pending', 0, NOW(), NOW()
             WHERE NOT EXISTS (
                   SELECT 1 FROM embedding_tasks
                    WHERE entity_type = :entityType
                      AND entity_id   = :entityId
                      AND status IN ('pending', 'processing')
                   )
            """)
    void enqueueIfAbsent(
            @Param("entityType") String entityType, @Param("entityId") Long entityId);

    /** Unconditional insert — used by reindexAll where duplicate tasks are harmless. */
    @Modifying
    @Query(
            """
            INSERT INTO embedding_tasks (entity_type, entity_id, status, attempts, created_at, updated_at)
            VALUES (:entityType, :entityId, 'pending', 0, NOW(), NOW())
            """)
    void enqueue(@Param("entityType") String entityType, @Param("entityId") Long entityId);
}
