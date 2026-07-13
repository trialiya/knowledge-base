package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Executor for embedding tasks.
 *
 * <p>Uses a virtual-thread-per-task executor so threads never block OS threads while waiting for
 * the OpenAI HTTP response. A {@link Semaphore} caps concurrent API calls at {@code
 * kb.embedding.workers} (default: 4) to respect rate limits and control cost.
 *
 * <p>Only created when {@code kb.search.semantic.enabled=true}; not active in H2 mode.
 */
@Component
@ConditionalOnProperty(name = "kb.search.semantic.enabled", havingValue = "true")
public class EmbeddingExecutor {

    private final Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore semaphore;

    public EmbeddingExecutor(EmbeddingConfiguration config) {
        this.semaphore = new Semaphore(config.workers());
    }

    /**
     * Number of semaphore permits currently available — used by the scheduler for adaptive batch
     * sizing so it does not claim more tasks than the executor can immediately start. Accurate
     * because {@link #submit} takes the permit synchronously before returning.
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * Tries to start {@code task} on a virtual thread, holding one semaphore permit for its whole
     * run. The permit is acquired synchronously, so a task never sits queued behind the semaphore
     * (where it could outlive the stuck-task timeout without ever running).
     *
     * @return {@code false} without executing when no permit is free — the caller keeps ownership
     *     of the task (e.g. returns it to the queue)
     */
    public boolean submit(Runnable task) {
        if (!semaphore.tryAcquire()) {
            return false;
        }
        delegate.execute(
                () -> {
                    try {
                        task.run();
                    } finally {
                        semaphore.release();
                    }
                });
        return true;
    }
}
