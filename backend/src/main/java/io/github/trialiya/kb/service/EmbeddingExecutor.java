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
 * the OpenAI HTTP response. A {@link Semaphore} caps concurrent API calls at
 * {@code kb.embedding.workers} (default: 4) to respect rate limits and control cost.
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
     * sizing so it does not claim more tasks than the executor can immediately start.
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * Submits {@code task} for async execution. Returns immediately. The task runs on a virtual
     * thread once a semaphore permit is available.
     */
    public void submit(Runnable task) {
        delegate.execute(
                () -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        task.run();
                    } finally {
                        semaphore.release();
                    }
                });
    }
}
