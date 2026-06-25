package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** Spring Data JDBC CRUD for {@link EmbeddingTaskEntity}. Used internally by {@link EmbeddingTaskRepository}. */
interface EmbeddingTaskCrudRepository extends CrudRepository<EmbeddingTaskEntity, Long> {

    /**
     * Inserts a pending task only when no pending task already exists for the same entity.
     * Allows re-queueing while a task is in 'starting' state (being processed), so that document
     * updates during processing are not silently dropped.
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
                      AND status      = 'pending'
                   )
            """)
    void enqueueIfAbsent(
            @Param("entityType") String entityType, @Param("entityId") Long entityId);
}
