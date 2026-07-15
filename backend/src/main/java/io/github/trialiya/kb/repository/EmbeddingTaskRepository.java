package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.embedding.EmbeddingEntityType;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskStatus;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for {@link EmbeddingTaskEntity}.
 *
 * <p>Plain {@link JdbcTemplate}: every operation relies on PostgreSQL-specific syntax ({@code FOR
 * UPDATE SKIP LOCKED}, {@code RETURNING}, {@code ON CONFLICT} against a partial unique index,
 * interval arithmetic), so Spring Data derived queries don't fit here.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EmbeddingTaskRepository {

    private final JdbcTemplate jdbc;

    /**
     * Atomically claims up to {@code batchSize} pending tasks: marks them {@code starting}, assigns
     * the batch's random {@code claimToken} and increments {@code attempts} in one {@code UPDATE …
     * RETURNING}, oldest first.
     *
     * <p>Skipped rows stay {@code pending} untouched: entities that already have a {@code starting}
     * task (the {@code NOT EXISTS} guard, so one entity is never embedded concurrently) and retries
     * whose linear backoff ({@code attempts * retryBackoffSeconds} since the last failure) has not
     * elapsed yet. {@code FOR UPDATE SKIP LOCKED} keeps concurrent claimers from grabbing the same
     * rows. The partial unique index {@code embedding_tasks_pending_unique} guarantees at most one
     * pending row per entity, so no in-batch dedup is needed.
     */
    @Transactional
    public List<EmbeddingTaskEntity> claimPending(int batchSize, int retryBackoffSeconds) {
        UUID claimToken = UUID.randomUUID();
        return jdbc.query(
                """
                UPDATE embedding_tasks
                   SET status      = 'starting',
                       updated_at  = NOW(),
                       attempts    = attempts + 1,
                       claim_token = ?
                 WHERE id IN (
                       SELECT id
                         FROM embedding_tasks e
                        WHERE e.status = 'pending'
                          AND e.updated_at <= NOW() - (? * e.attempts) * interval '1 second'
                          AND NOT EXISTS (
                              SELECT 1 FROM embedding_tasks s
                               WHERE s.entity_type = e.entity_type
                                 AND s.entity_id   = e.entity_id
                                 AND s.status      = 'starting'
                          )
                        ORDER BY e.created_at
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                       )
                RETURNING id, entity_type, entity_id, status, attempts, created_at, updated_at, claim_token
                """,
                TASK_ROW_MAPPER,
                claimToken,
                retryBackoffSeconds,
                batchSize);
    }

    /**
     * Returns {@code true} if the task is still in {@code starting} state with the given token —
     * i.e. the reaper has not yet reclaimed it. Workers use this to skip the AI call when the task
     * was already handed to someone else.
     */
    public boolean isMyClaimValid(Long id, UUID claimToken) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM embedding_tasks WHERE id = ? AND claim_token = ? AND status = 'starting'",
                        Integer.class,
                        id,
                        claimToken);
        return count != null && count > 0;
    }

    /** Marks a task {@code done}; no-op if the {@code claimToken} no longer matches. */
    public void markDone(Long id, UUID claimToken) {
        jdbc.update(
                "UPDATE embedding_tasks SET status = 'done', updated_at = NOW()"
                        + " WHERE id = ? AND claim_token = ? AND status = 'starting'",
                id,
                claimToken);
    }

    /**
     * Marks a task permanently {@code failed}; no-op if the {@code claimToken} no longer matches.
     */
    public void markFailed(Long id, UUID claimToken) {
        jdbc.update(
                "UPDATE embedding_tasks SET status = 'failed', updated_at = NOW()"
                        + " WHERE id = ? AND claim_token = ? AND status = 'starting'",
                id,
                claimToken);
    }

    /**
     * Returns a claimed task to the queue for retry; no-op if the {@code claimToken} no longer
     * matches.
     *
     * <p>If a newer {@code pending} task for the same entity was enqueued while this one was in
     * flight, the retry is redundant — the newer task re-embeds the same entity from fresh data —
     * so the row is marked {@code superseded} instead. This also avoids violating the {@code
     * embedding_tasks_pending_unique} index.
     */
    public void resetToPending(Long id, UUID claimToken) {
        jdbc.update(
                """
                UPDATE embedding_tasks t
                   SET status = CASE WHEN EXISTS (
                                    SELECT 1 FROM embedding_tasks p
                                     WHERE p.entity_type = t.entity_type
                                       AND p.entity_id   = t.entity_id
                                       AND p.status      = 'pending')
                                THEN 'superseded' ELSE 'pending' END,
                       updated_at  = NOW(),
                       claim_token = NULL
                 WHERE t.id = ? AND t.claim_token = ? AND t.status = 'starting'
                """,
                id,
                claimToken);
    }

    /**
     * Returns a claim whose worker never ran (e.g. the executor had no free permit) — like {@link
     * #resetToPending}, but also rolls back the {@code attempts} increment made by {@link
     * #claimPending}, since nothing was actually attempted.
     */
    public void releaseClaim(Long id, UUID claimToken) {
        jdbc.update(
                """
                UPDATE embedding_tasks t
                   SET status = CASE WHEN EXISTS (
                                    SELECT 1 FROM embedding_tasks p
                                     WHERE p.entity_type = t.entity_type
                                       AND p.entity_id   = t.entity_id
                                       AND p.status      = 'pending')
                                THEN 'superseded' ELSE 'pending' END,
                       attempts    = t.attempts - 1,
                       updated_at  = NOW(),
                       claim_token = NULL
                 WHERE t.id = ? AND t.claim_token = ? AND t.status = 'starting'
                """,
                id,
                claimToken);
    }

    /**
     * Reaper: finds tasks stuck in {@code starting} longer than {@code stuckMinutes} and either
     * returns them to the queue (if under {@code maxAttempts}; {@code superseded} when a newer
     * pending task for the same entity already exists — see {@link #resetToPending}) or marks them
     * {@code failed}.
     *
     * @return total rows affected
     */
    @Transactional
    public int resetStuck(int stuckMinutes, int maxAttempts) {
        int reset =
                jdbc.update(
                        """
                UPDATE embedding_tasks t
                   SET status = CASE WHEN EXISTS (
                                    SELECT 1 FROM embedding_tasks p
                                     WHERE p.entity_type = t.entity_type
                                       AND p.entity_id   = t.entity_id
                                       AND p.status      = 'pending')
                                THEN 'superseded' ELSE 'pending' END,
                       updated_at  = NOW(),
                       claim_token = NULL
                 WHERE t.status = 'starting'
                   AND t.updated_at < NOW() - ? * interval '1 minute'
                   AND t.attempts < ?
                """,
                        stuckMinutes,
                        maxAttempts);
        int failed =
                jdbc.update(
                        """
                UPDATE embedding_tasks
                   SET status = 'failed', updated_at = NOW()
                 WHERE status = 'starting'
                   AND updated_at < NOW() - ? * interval '1 minute'
                   AND attempts >= ?
                """,
                        stuckMinutes,
                        maxAttempts);
        return reset + failed;
    }

    /**
     * Deletes old {@code done}/{@code failed}/{@code superseded} rows to prevent unbounded growth.
     *
     * @return number of rows deleted
     */
    @Transactional
    public int cleanupCompleted(int retentionDays) {
        return jdbc.update(
                """
                DELETE FROM embedding_tasks
                 WHERE status IN ('done', 'failed', 'superseded')
                   AND updated_at < NOW() - ? * interval '1 day'
                """,
                retentionDays);
    }

    /**
     * Enqueues a pending task unless one already exists for this entity. Race-safe: relies on the
     * partial unique index {@code embedding_tasks_pending_unique} via {@code ON CONFLICT DO
     * NOTHING}, so concurrent enqueues for the same entity never throw into the caller's
     * transaction.
     */
    public void enqueueIfAbsent(EmbeddingEntityType entityType, Long entityId) {
        jdbc.update(
                """
                INSERT INTO embedding_tasks (entity_type, entity_id, status, attempts, created_at, updated_at)
                VALUES (?, ?, 'pending', 0, NOW(), NOW())
                ON CONFLICT (entity_type, entity_id) WHERE status = 'pending' DO NOTHING
                """,
                entityType.getValue(),
                entityId);
    }

    private static final RowMapper<EmbeddingTaskEntity> TASK_ROW_MAPPER =
            (rs, rowNum) -> {
                EmbeddingTaskEntity e = new EmbeddingTaskEntity();
                e.setId(rs.getLong("id"));
                e.setEntityType(EmbeddingEntityType.fromValue(rs.getString("entity_type")));
                e.setEntityId(rs.getLong("entity_id"));
                e.setStatus(EmbeddingTaskStatus.fromValue(rs.getString("status")));
                e.setAttempts(rs.getInt("attempts"));
                Timestamp created = rs.getTimestamp("created_at");
                if (created != null) {
                    e.setCreatedAt(created.toInstant().atOffset(ZoneOffset.UTC));
                }
                Timestamp updated = rs.getTimestamp("updated_at");
                if (updated != null) {
                    e.setUpdatedAt(updated.toInstant().atOffset(ZoneOffset.UTC));
                }
                e.setClaimToken((UUID) rs.getObject("claim_token"));
                return e;
            };
}
