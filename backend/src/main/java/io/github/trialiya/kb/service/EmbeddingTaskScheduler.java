package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.model.embedding.EmbeddingTaskEntity;
import io.github.trialiya.kb.repository.EmbeddingTaskRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code embedding_tasks} and dispatches work to {@link EmbeddingExecutor}.
 *
 * <p>Only active when {@code kb.search.semantic.enabled=true} (not in H2 mode).
 *
 * <h3>Flow per poll tick</h3>
 *
 * <ol>
 *   <li>Adaptive batch size = {@code min(pollBatchSize, executor.availablePermits())} — skips the
 *       poll entirely when the executor is saturated.
 *   <li>{@link EmbeddingTaskRepository#claimPending} atomically marks tasks {@code starting},
 *       assigns a random {@code claim_token}, and deduplicates concurrent tasks for the same entity
 *       using {@code FOR UPDATE SKIP LOCKED} + a {@code NOT EXISTS} guard.
 *   <li>Each claimed task is submitted to {@link EmbeddingExecutor}: a virtual thread acquires a
 *       semaphore permit, validates the claim token, calls the AI API, persists the vector, then
 *       marks the task {@code done}, {@code pending} (retry), or {@code failed}.
 * </ol>
 *
 * <h3>Stuck-task reaper</h3>
 *
 * <p>A separate scheduled method ({@link #recoverAndCleanup}) periodically resets tasks that have
 * been stuck in {@code starting} beyond {@code kb.embedding.stuck-timeout-minutes}, and deletes
 * old terminal rows ({@code done}/{@code failed}/{@code superseded}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kb.search.semantic.enabled", havingValue = "true")
public class EmbeddingTaskScheduler {

    private final EmbeddingTaskRepository taskRepo;
    private final EmbeddingExecutor executor;
    private final SemanticSearchService searchService;
    private final int pollBatchSize;
    private final int maxAttempts;
    private final int stuckTimeoutMinutes;
    private final int cleanupRetentionDays;

    public EmbeddingTaskScheduler(
            EmbeddingTaskRepository taskRepo,
            EmbeddingExecutor executor,
            SemanticSearchService searchService,
            EmbeddingConfiguration config) {
        this.taskRepo = taskRepo;
        this.executor = executor;
        this.searchService = searchService;
        this.pollBatchSize = config.pollBatchSize();
        this.maxAttempts = config.maxAttempts();
        this.stuckTimeoutMinutes = config.stuckTimeoutMinutes();
        this.cleanupRetentionDays = config.cleanupRetentionDays();
    }

    @Scheduled(fixedDelayString = "${kb.embedding.poll-interval-ms:1000}")
    public void poll() {
        int available = executor.availablePermits();
        if (available == 0) {
            return;
        }
        int batchSize = Math.min(pollBatchSize, available);
        // claimPending() manages its own TX and commits before we submit to the executor,
        // so workers never race against an uncommitted status change.
        List<EmbeddingTaskEntity> tasks = taskRepo.claimPending(batchSize);
        if (!tasks.isEmpty()) {
            log.debug("Claimed {} embedding task(s)", tasks.size());
        }
        tasks.forEach(t -> executor.submit(() -> processTask(t)));
    }

    @Scheduled(fixedDelayString = "${kb.embedding.stuck-check-ms:300000}")
    public void recoverAndCleanup() {
        int recovered = taskRepo.resetStuck(stuckTimeoutMinutes, maxAttempts);
        if (recovered > 0) {
            log.warn("Recovered {} stuck embedding task(s) (timeout={}m)", recovered, stuckTimeoutMinutes);
        }
        int deleted = taskRepo.cleanupCompleted(cleanupRetentionDays);
        if (deleted > 0) {
            log.debug("Cleaned up {} terminal embedding task row(s)", deleted);
        }
    }

    private void processTask(EmbeddingTaskEntity task) {
        // Guard against the reaper resetting this task while we were waiting for a semaphore permit.
        if (!taskRepo.isMyClaimValid(task.getId(), task.getClaimToken())) {
            log.debug(
                    "Embedding task id={} claim_token no longer valid, skipping",
                    task.getId());
            return;
        }
        try {
            switch (task.getEntityType()) {
                case "document" -> searchService.indexDocumentById(task.getEntityId());
                case "attachment" -> searchService.indexAttachmentById(task.getEntityId());
                default ->
                        log.warn(
                                "Unknown entity type in embedding task id={}: {}",
                                task.getId(),
                                task.getEntityType());
            }
            taskRepo.markDone(task.getId(), task.getClaimToken());
            log.debug(
                    "Embedding task id={} type={} entity={} done",
                    task.getId(),
                    task.getEntityType(),
                    task.getEntityId());
        } catch (Exception ex) {
            if (task.getAttempts() >= maxAttempts) {
                taskRepo.markFailed(task.getId(), task.getClaimToken());
                log.warn(
                        "Embedding task id={} type={} entity={} permanently failed after {} attempt(s): {}",
                        task.getId(),
                        task.getEntityType(),
                        task.getEntityId(),
                        task.getAttempts(),
                        ex.getMessage());
            } else {
                taskRepo.resetToPending(task.getId(), task.getClaimToken());
                log.warn(
                        "Embedding task id={} type={} entity={} failed (attempt {}/{}), will retry: {}",
                        task.getId(),
                        task.getEntityType(),
                        task.getEntityId(),
                        task.getAttempts(),
                        maxAttempts,
                        ex.getMessage());
            }
        }
    }
}
