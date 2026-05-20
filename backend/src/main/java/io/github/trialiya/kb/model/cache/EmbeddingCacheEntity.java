package io.github.trialiya.kb.model.cache;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One cached embedding row.
 *
 * <p>The primary lookup key is {@code (textHash, model)}: the same text embedded with a different
 * model must be stored separately. {@code lastUsedAt} is updated on every cache hit so that a
 * least-recently-used eviction policy can be applied by {@code EmbeddingCacheCleanupTask}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("embedding_cache")
public class EmbeddingCacheEntity {

    @Id private Long id;

    /** Lowercase SHA-256 hex digest of the raw input text (64 chars). */
    private String textHash;

    /** Model name used to produce the embedding, e.g. {@code "text-embedding-3-small"}. */
    private String model;

    /**
     * The cached vector. Mapped to the Postgres {@code vector} column via {@link
     * FloatArrayToVectorConverter}.
     */
    private float[] embedding;

    private OffsetDateTime createdAt;

    /** Bumped on every cache hit for LRU-style eviction. */
    private OffsetDateTime lastUsedAt;
}
