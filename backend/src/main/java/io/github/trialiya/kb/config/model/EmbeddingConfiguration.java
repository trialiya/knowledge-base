package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.embedding.*} in {@code application.yaml}.
 *
 * <pre>
 * kb:
 *   embedding:
 *     model: text-embedding-3-small
 *     reindex-batch-size: 50
 *     workers: 4
 *     poll-batch-size: 20
 *     max-attempts: 3
 *     retry-backoff-seconds: 30
 *     stuck-timeout-minutes: 10
 *     cleanup-retention-days: 7
 *     poll-interval-ms: 1000    # read by @Scheduled placeholders, not bound here
 *     stuck-check-ms: 300000    # read by @Scheduled placeholders, not bound here
 *     cache:
 *       enabled: true
 *       ttl-days: 30
 *       cleanup-cron: "0 0 2 * * *"
 *     chunker:
 *       max-tokens: 512
 *       overlap-tokens: 64
 * </pre>
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
