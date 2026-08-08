package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.embedding.*} — the keys, their values and what each one means are in {@code
 * application.yaml}.
 *
 * <p>{@code pollIntervalMs}/{@code stuckCheckMs} also live as raw placeholders in {@code
 * EmbeddingTaskScheduler}'s {@code @Scheduled(fixedDelayString)} — an annotation cannot read a
 * bean. They are bound here as well so that everything the Admin panel reports about the queue
 * comes from this one record, instead of the controller re-declaring the two keys and their
 * defaults a third time.
 */
@ConfigurationProperties(prefix = "kb.embedding")
public record EmbeddingConfiguration(
        String model,
        int reindexBatchSize,
        int workers,
        int pollBatchSize,
        int maxAttempts,
        int retryBackoffSeconds,
        int stuckTimeoutMinutes,
        int cleanupRetentionDays,
        long pollIntervalMs,
        long stuckCheckMs,
        EmbeddingCacheConfiguration cache,
        EmbeddingChunkerConfiguration chunker) {

    /**
     * Binding for {@code kb.embedding.cache.*}.
     *
     * @param enabled set to {@code false} to bypass the Postgres cache entirely.
     * @param ttlDays rows not accessed for this many days are deleted by the cleanup task.
     * @param cleanupCron Spring cron expression for the cleanup job.
     */
    public record EmbeddingCacheConfiguration(boolean enabled, int ttlDays, String cleanupCron) {}

    /**
     * Binding for {@code kb.embedding.chunker.*}.
     *
     * @param maxTokens maximum tokens per chunk.
     * @param overlapTokens token overlap between adjacent chunks.
     */
    public record EmbeddingChunkerConfiguration(int maxTokens, int overlapTokens) {}
}
