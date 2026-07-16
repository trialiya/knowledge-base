package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.model.embedding.EmbeddingEntityType;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskStatus;
import io.github.trialiya.kb.repository.EmbeddingTaskRepository;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Интеграционные тесты очереди {@code embedding_tasks} на настоящем PostgreSQL: {@code FOR UPDATE
 * SKIP LOCKED}, {@code RETURNING}, {@code ON CONFLICT} по частичному уникальному индексу и
 * интервальная арифметика — всё это на H2 не проверить.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    CommonConfig.class,
    JdbcConfig.class,
    PgVectorJdbcConfig.class,
    EmbeddingTaskRepository.class
})
class EmbeddingTaskRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired private EmbeddingTaskRepository repo;
    @Autowired private JdbcTemplate jdbc;

    // ── Постановка в очередь ─────────────────────────────────────────────────

    @Test
    void enqueueIsIdempotentWhilePending() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 101L);
        repo.enqueueIfAbsent(
                EmbeddingEntityType.DOCUMENT, 101L); // ON CONFLICT DO NOTHING, не исключение

        assertThat(countByStatus("document", 101L, "pending")).isEqualTo(1);
    }

    @Test
    void enqueueAllowedWhileEntityIsStarting() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 102L);
        repo.claimPending(10, 0);

        // Документ обновили, пока старая задача в обработке — новая pending-строка разрешена.
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 102L);

        assertThat(countByStatus("document", 102L, "starting")).isEqualTo(1);
        assertThat(countByStatus("document", 102L, "pending")).isEqualTo(1);
    }

    // ── Claim ────────────────────────────────────────────────────────────────

    @Test
    void claimRespectsBatchSizeAndNeverDropsTheRest() {
        for (long id = 1; id <= 5; id++) {
            repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 200 + id);
        }

        List<EmbeddingTaskEntity> first = repo.claimPending(2, 0);
        assertThat(first).hasSize(2);

        // Остальные три остаются pending с нетронутыми attempts — ничего не superseded.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM embedding_tasks WHERE status = 'pending' AND attempts = 0",
                                Integer.class))
                .isEqualTo(3);

        List<EmbeddingTaskEntity> second = repo.claimPending(10, 0);
        assertThat(second).hasSize(3);

        // Суммарно обработаны все 5 сущностей, без пересечений.
        assertThat(first)
                .extracting(EmbeddingTaskEntity::getEntityId)
                .doesNotContainAnyElementsOf(
                        second.stream().map(EmbeddingTaskEntity::getEntityId).toList());
    }

    @Test
    void claimIsFifoAndSetsClaimFields() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 301L);
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 302L);
        // Внутри одной транзакции NOW() не меняется — состариваем первую строку явно.
        jdbc.update(
                "UPDATE embedding_tasks SET created_at = NOW() - interval '1 minute' WHERE entity_id = 301");

        List<EmbeddingTaskEntity> claimed = repo.claimPending(1, 0);

        assertThat(claimed).hasSize(1);
        EmbeddingTaskEntity task = claimed.getFirst();
        assertThat(task.getEntityId()).isEqualTo(301L); // старейшая — первой
        assertThat(task.getStatus()).isEqualTo(EmbeddingTaskStatus.STARTING);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getClaimToken()).isNotNull();
    }

    @Test
    void claimSkipsEntityAlreadyStarting() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 303L);
        repo.claimPending(10, 0);
        repo.enqueueIfAbsent(
                EmbeddingEntityType.DOCUMENT, 303L); // новая pending, пока старая в обработке

        // Пока по сущности есть starting-задача, её новая pending-строка не выдаётся.
        assertThat(repo.claimPending(10, 0)).isEmpty();
    }

    @Test
    void claimHonoursRetryBackoff() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 304L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();
        repo.resetToPending(task.getId(), task.getClaimToken());

        // attempts=1, updated_at=сейчас: при большом бэкоффе задача ещё «остывает».
        assertThat(repo.claimPending(10, 3600)).isEmpty();

        // Без бэкоффа выдаётся сразу, attempts растёт.
        List<EmbeddingTaskEntity> retried = repo.claimPending(10, 0);
        assertThat(retried).hasSize(1);
        assertThat(retried.getFirst().getAttempts()).isEqualTo(2);
    }

    // ── Завершение: claim_token защищает статусы ─────────────────────────────

    @Test
    void markDoneIsNoOpWithForeignToken() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 305L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();

        repo.markDone(task.getId(), UUID.randomUUID());
        assertThat(statusOf(task.getId())).isEqualTo("starting");
        assertThat(repo.isMyClaimValid(task.getId(), task.getClaimToken())).isTrue();

        repo.markDone(task.getId(), task.getClaimToken());
        assertThat(statusOf(task.getId())).isEqualTo("done");
        assertThat(repo.isMyClaimValid(task.getId(), task.getClaimToken())).isFalse();
    }

    @Test
    void resetToPendingReturnsTaskToQueue() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 306L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();

        repo.resetToPending(task.getId(), task.getClaimToken());

        assertThat(statusOf(task.getId())).isEqualTo("pending");
        assertThat(claimTokenOf(task.getId())).isNull();
    }

    @Test
    void resetToPendingSupersedesWhenNewerPendingExists() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 307L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();
        // Пока задача в полёте, сущность обновили — появилась новая pending-строка.
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 307L);

        // Возврат в pending нарушил бы embedding_tasks_pending_unique — строка схлопывается.
        repo.resetToPending(task.getId(), task.getClaimToken());

        assertThat(statusOf(task.getId())).isEqualTo("superseded");
        assertThat(countByStatus("document", 307L, "pending")).isEqualTo(1);
    }

    @Test
    void releaseClaimDoesNotBurnAttempt() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 308L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();
        assertThat(task.getAttempts()).isEqualTo(1);

        repo.releaseClaim(task.getId(), task.getClaimToken());

        assertThat(statusOf(task.getId())).isEqualTo("pending");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM embedding_tasks WHERE id = ?",
                                Integer.class,
                                task.getId()))
                .isZero();
    }

    // ── Reaper и очистка ─────────────────────────────────────────────────────

    @Test
    void resetStuckSplitsByAttempts() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 309L);
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 310L);
        List<EmbeddingTaskEntity> claimed = repo.claimPending(10, 0);
        assertThat(claimed).hasSize(2);
        Long retryableId = idOfEntity(claimed, 309L);
        Long exhaustedId = idOfEntity(claimed, 310L);

        jdbc.update(
                "UPDATE embedding_tasks SET updated_at = NOW() - interval '1 hour' WHERE id IN (?, ?)",
                retryableId,
                exhaustedId);
        jdbc.update("UPDATE embedding_tasks SET attempts = 3 WHERE id = ?", exhaustedId);

        int affected = repo.resetStuck(10, 3);

        assertThat(affected).isEqualTo(2);
        assertThat(statusOf(retryableId)).isEqualTo("pending");
        assertThat(claimTokenOf(retryableId)).isNull();
        assertThat(statusOf(exhaustedId)).isEqualTo("failed");
    }

    @Test
    void resetStuckIgnoresFreshStartingTasks() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 311L);
        EmbeddingTaskEntity task = repo.claimPending(10, 0).getFirst();

        assertThat(repo.resetStuck(10, 3)).isZero();
        assertThat(statusOf(task.getId())).isEqualTo("starting");
    }

    @Test
    void cleanupDeletesOnlyOldTerminalRows() {
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 312L); // останется pending — не трогать
        repo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, 313L);
        EmbeddingTaskEntity done = repo.claimPending(1, 0).getFirst();
        long stillPending = done.getEntityId() == 312L ? 313L : 312L;
        repo.markDone(done.getId(), done.getClaimToken());
        jdbc.update(
                "UPDATE embedding_tasks SET updated_at = NOW() - interval '30 days' WHERE id = ?",
                done.getId());

        int deleted = repo.cleanupCompleted(7);

        assertThat(deleted).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM embedding_tasks WHERE id = ?",
                                Integer.class,
                                done.getId()))
                .isZero();
        assertThat(countByStatus("document", stillPending, "pending")).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int countByStatus(String entityType, Long entityId, String status) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM embedding_tasks WHERE entity_type = ? AND entity_id = ? AND status = ?",
                        Integer.class,
                        entityType,
                        entityId,
                        status);
        return count == null ? 0 : count;
    }

    private String statusOf(Long id) {
        return jdbc.queryForObject(
                "SELECT status FROM embedding_tasks WHERE id = ?", String.class, id);
    }

    private UUID claimTokenOf(Long id) {
        return jdbc.queryForObject(
                "SELECT claim_token FROM embedding_tasks WHERE id = ?", UUID.class, id);
    }

    private static Long idOfEntity(List<EmbeddingTaskEntity> tasks, long entityId) {
        return tasks.stream()
                .filter(t -> t.getEntityId() == entityId)
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
