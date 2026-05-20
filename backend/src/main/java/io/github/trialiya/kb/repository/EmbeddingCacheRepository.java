package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.cache.EmbeddingCacheEntity;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC repository for {@link EmbeddingCacheEntity}.
 *
 * <p>Lookup is by {@code (textHash, model)}. The {@code touchLastUsed} method updates {@code
 * last_used_at} in-place without loading the full row, keeping cache-hit overhead minimal.
 */
public interface EmbeddingCacheRepository extends CrudRepository<EmbeddingCacheEntity, Long> {

    /** Returns the cached row for the given hash + model, or empty if not cached. */
    @Query("SELECT * FROM embedding_cache WHERE text_hash = :hash AND model = :model LIMIT 1")
    Optional<EmbeddingCacheEntity> findByTextHashAndModel(
            @Param("hash") String hash, @Param("model") String model);

    /**
     * Bumps {@code last_used_at} for a cache hit without a round-trip load + save.
     *
     * @param hash the text hash
     * @param model the embedding model name
     * @param touchedAt the new timestamp (usually {@code OffsetDateTime.now()})
     */
    @Modifying
    @Query(
            """
            UPDATE embedding_cache
               SET last_used_at = :touchedAt
             WHERE text_hash = :hash
               AND model     = :model
            """)
    void touchLastUsed(
            @Param("hash") String hash,
            @Param("model") String model,
            @Param("touchedAt") OffsetDateTime touchedAt);

    /**
     * Deletes all rows that have not been accessed since {@code before}. Called by {@code
     * EmbeddingCacheCleanupTask}.
     *
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM embedding_cache WHERE last_used_at < :before")
    int deleteByLastUsedAtBefore(@Param("before") OffsetDateTime before);

    /** Total number of rows in the cache table (for metrics / health checks). */
    @Query("SELECT COUNT(*) FROM embedding_cache")
    long countAll();
}
