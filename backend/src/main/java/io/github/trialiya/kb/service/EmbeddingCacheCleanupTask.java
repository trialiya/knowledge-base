package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.repository.EmbeddingCacheRepository;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically evicts stale rows from {@code embedding_cache}.
 *
 * <h3>Eviction policy</h3>
 *
 * A row is deleted when its {@code last_used_at} timestamp is older than {@code
 * kb.embedding.cache.ttl-days} (default: 30 days). This implements an LRU-style policy: vectors
 * that are frequently looked up keep their {@code last_used_at} fresh and survive; vectors for
 * texts that are never queried again are reclaimed.
 *
 * <h3>Schedule</h3>
 *
 * By default the job runs at 02:00 every night (cron {@code 0 0 2 * * *}). Override via {@code
 * kb.embedding.cache.cleanup-cron} in {@code application.yaml}.
 *
 * <h3>Configuration</h3>
 *
 * <pre>
 * kb:
 *   embedding:
 *     cache:
 *       enabled: true
 *       ttl-days: 30
 *       cleanup-cron: "0 0 2 * * *"
 * </pre>
 */
@Slf4j
@Component
public class EmbeddingCacheCleanupTask {

    private final EmbeddingCacheRepository cacheRepo;
    private final int ttlDays;

    public EmbeddingCacheCleanupTask(
            EmbeddingCacheRepository cacheRepo, EmbeddingConfiguration embeddingConfig) {
        this.cacheRepo = cacheRepo;
        this.ttlDays = embeddingConfig.cache().ttlDays();
    }

    // ── Scheduled job ─────────────────────────────────────────────────────────

    /**
     * Deletes stale cache entries.
     *
     * <p>The cron expression is read from {@code kb.embedding.cache.cleanup-cron}; it defaults to
     * {@code "0 0 2 * * *"} (every day at 02:00 server time).
     *
     * <p>The method is {@code @Transactional} so the DELETE and subsequent COUNT run in the same
     * transaction and provide a consistent view of the row count in the log.
     */
    @Scheduled(cron = "${kb.embedding.cache.cleanup-cron:0 0 2 * * *}")
    @Transactional
    public void evictStaleEntries() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(ttlDays);
        log.info(
                "Embedding cache cleanup started — deleting rows with last_used_at < {} (ttl={}d)",
                cutoff,
                ttlDays);

        int deleted = cacheRepo.deleteByLastUsedAtBefore(cutoff);
        long remaining = cacheRepo.countAll();

        log.info("Embedding cache cleanup finished — deleted={} remaining={}", deleted, remaining);
    }

    // ── Manual trigger (admin endpoint / actuator) ────────────────────────────

    /**
     * Same logic as the scheduled job but callable on demand (e.g. from an admin REST endpoint or
     * an Actuator custom endpoint).
     *
     * @param customTtlDays if {@code null}, falls back to the configured {@link #ttlDays}
     * @return number of deleted rows
     */
    @Transactional
    public int evictNow(@Nullable Integer customTtlDays) {
        int effectiveTtl = customTtlDays != null ? customTtlDays : ttlDays;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(effectiveTtl);
        log.info("Manual cache eviction triggered — cutoff={} ttl={}d", cutoff, effectiveTtl);

        int deleted = cacheRepo.deleteByLastUsedAtBefore(cutoff);
        log.info("Manual eviction done — deleted={} remaining={}", deleted, cacheRepo.countAll());
        return deleted;
    }
}
