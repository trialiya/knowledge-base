package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public repository for {@link EmbeddingTaskEntity}.
 *
 * <p>Delegates simple enqueue operations to {@link EmbeddingTaskCrudRepository}. Uses
 * {@link JdbcTemplate}/{@link NamedParameterJdbcTemplate} for operations that require
 * PostgreSQL-specific syntax ({@code FOR UPDATE SKIP LOCKED}, {@code RETURNING}, {@code INTERVAL}).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EmbeddingTaskRepository {

    private final EmbeddingTaskCrudRepository crud;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    /**
     * Atomically claims up to {@code batchSize} pending tasks:
     *
     * <ol>
     *   <li>Selects up to {@code batchSize * 3} pending rows (overScan headroom for dedup) ordered
     *       newest-first, skipping entities that already have a {@code starting} task.
     *   <li>Marks them {@code starting}, assigns the batch's {@code claimToken}, increments
     *       {@code attempts} — all in one {@code UPDATE … RETURNING}.
     *   <li>Java-side dedup: keeps the newest task per entity (first in DESC order), supersedes
     *       duplicates and any excess beyond {@code batchSize}.
     * </ol>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} ensures concurrent callers never grab the same rows.
     */
    @Transactional
    public List<EmbeddingTaskEntity> claimPending(int batchSize) {
        UUID claimToken = UUID.randomUUID();
        int overScan = batchSize * 3;

        List<EmbeddingTaskEntity> candidates = jdbc.query(
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
                          AND NOT EXISTS (
                              SELECT 1 FROM embedding_tasks s
                               WHERE s.entity_type = e.entity_type
                                 AND s.entity_id   = e.entity_id
                                 AND s.status      = 'starting'
                          )
                        ORDER BY e.created_at DESC
                        LIMIT ?
                          FOR UPDATE SKIP LOCKED
                       )
                RETURNING id, entity_type, entity_id, status, attempts, created_at, updated_at, claim_token
                """,
                TASK_ROW_MAPPER,
                claimToken,
                overScan);

        // Keep newest per entity (DESC order means index 0 = newest); supersede the rest.
        Map<String, EmbeddingTaskEntity> winners = new LinkedHashMap<>();
        List<Long> supersededIds = new ArrayList<>();

        for (EmbeddingTaskEntity t : candidates) {
            String key = t.getEntityType() + ":" + t.getEntityId();
            if (winners.containsKey(key)) {
                supersededIds.add(t.getId());
            } else {
                winners.put(key, t);
            }
        }

        List<EmbeddingTaskEntity> result = new ArrayList<>(winners.values());

        // Trim to batchSize and supersede any overflow.
        if (result.size() > batchSize) {
            result.subList(batchSize, result.size()).forEach(t -> supersededIds.add(t.getId()));
            result = new ArrayList<>(result.subList(0, batchSize));
        }

        if (!supersededIds.isEmpty()) {
            namedJdbc.update(
                    "UPDATE embedding_tasks SET status = 'superseded', updated_at = NOW() WHERE id IN (:ids)",
                    Map.of("ids", supersededIds));
            log.debug("Superseded {} duplicate/overflow embedding task(s)", supersededIds.size());
        }

        return result;
    }

    /**
     * Returns {@code true} if the task is still in {@code starting} state with the given token —
     * i.e. the reaper has not yet reset or superseded it. Workers use this to skip the AI call when
     * the task was already reclaimed.
     */
    public boolean isMyClaimValid(Long id, UUID claimToken) {
        Integer count = jdbc.queryForObject(
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

    /** Marks a task permanently {@code failed}; no-op if the {@code claimToken} no longer matches. */
    public void markFailed(Long id, UUID claimToken) {
        jdbc.update(
                "UPDATE embedding_tasks SET status = 'failed', updated_at = NOW()"
                        + " WHERE id = ? AND claim_token = ? AND status = 'starting'",
                id,
                claimToken);
    }

    /**
     * Resets a task back to {@code pending} for retry; no-op if the {@code claimToken} no longer
     * matches. Clears {@code claim_token} so the next poll can claim it freely.
     */
    public void resetToPending(Long id, UUID claimToken) {
        jdbc.update(
                "UPDATE embedding_tasks SET status = 'pending', updated_at = NOW(), claim_token = NULL"
                        + " WHERE id = ? AND claim_token = ? AND status = 'starting'",
                id,
                claimToken);
    }

    /**
     * Reaper: finds tasks stuck in {@code starting} longer than {@code stuckMinutes} and either
     * resets them to {@code pending} (if under {@code maxAttempts}) or marks them {@code failed}.
     *
     * @return total rows affected
     */
    @Transactional
    public int resetStuck(int stuckMinutes, int maxAttempts) {
        int reset = jdbc.update(
                String.format("""
                        UPDATE embedding_tasks
                           SET status = 'pending', updated_at = NOW(), claim_token = NULL
                         WHERE status = 'starting'
                           AND updated_at < NOW() - INTERVAL '%d minutes'
                           AND attempts < ?
                        """,
                        stuckMinutes),
                maxAttempts);
        int failed = jdbc.update(
                String.format("""
                        UPDATE embedding_tasks
                           SET status = 'failed', updated_at = NOW()
                         WHERE status = 'starting'
                           AND updated_at < NOW() - INTERVAL '%d minutes'
                           AND attempts >= ?
                        """,
                        stuckMinutes),
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
                String.format("""
                        DELETE FROM embedding_tasks
                         WHERE status IN ('done', 'failed', 'superseded')
                           AND updated_at < NOW() - INTERVAL '%d days'
                        """,
                        retentionDays));
    }

    /** Enqueue only when no pending task for this entity already exists. */
    public void enqueueIfAbsent(String entityType, Long entityId) {
        crud.enqueueIfAbsent(entityType, entityId);
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
                e.setClaimToken((UUID) rs.getObject("claim_token"));
                return e;
            };
}
