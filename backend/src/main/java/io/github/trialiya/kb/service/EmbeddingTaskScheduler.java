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
 *   <li>{@link EmbeddingTaskRepository#claimPending} atomically marks up to {@code
 *       kb.embedding.poll-batch-size} rows as {@code processing} using {@code FOR UPDATE SKIP
 *       LOCKED} — safe under concurrent instances.
 *   <li>Each claimed task is submitted to {@link EmbeddingExecutor}: a virtual thread acquires a
 *       semaphore permit, calls the AI API, persists the vector, then marks the task {@code done}
 *       or {@code failed}.
 * </ol>
 *
 * <p>Poll interval is controlled by {@code kb.embedding.poll-interval-ms} (default: 1 000 ms).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kb.search.semantic.enabled", havingValue = "true")
public class EmbeddingTaskScheduler {

    private final EmbeddingTaskRepository taskRepo;
    private final EmbeddingExecutor executor;
    private final SemanticSearchService searchService;
    private final int pollBatchSize;

    public EmbeddingTaskScheduler(
            EmbeddingTaskRepository taskRepo,
            EmbeddingExecutor executor,
            SemanticSearchService searchService,
            EmbeddingConfiguration config) {
        this.taskRepo = taskRepo;
        this.executor = executor;
        this.searchService = searchService;
        this.pollBatchSize = config.pollBatchSize();
    }

    @Scheduled(fixedDelayString = "${kb.embedding.poll-interval-ms:1000}")
    public void poll() {
        // claimPending() manages its own TX; it commits before we submit to the executor,
        // so workers never race against an uncommitted status change.
        List<EmbeddingTaskEntity> tasks = taskRepo.claimPending(pollBatchSize);
        if (!tasks.isEmpty()) {
            log.debug("Claimed {} embedding task(s)", tasks.size());
        }
        tasks.forEach(t -> executor.submit(() -> processTask(t)));
    }

    private void processTask(EmbeddingTaskEntity task) {
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
            taskRepo.markDone(task.getId());
            log.debug(
                    "Embedding task id={} type={} entity={} completed",
                    task.getId(),
                    task.getEntityType(),
                    task.getEntityId());
        } catch (Exception ex) {
            taskRepo.markFailed(task.getId());
            log.warn(
                    "Embedding task id={} type={} entity={} failed (attempt {}): {}",
                    task.getId(),
                    task.getEntityType(),
                    task.getEntityId(),
                    task.getAttempts(),
                    ex.getMessage());
        }
    }
}
