package io.github.trialiya.kb.model.search;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Result row returned by a semantic (vector) search query.
 *
 * @param id document id (stringified Long)
 * @param title document title
 * @param description document description / body (may be null)
 * @param updatedAt last modification timestamp
 * @param similarity cosine similarity in [0, 1] – higher is more relevant
 */
public record SemanticSearchResult(
        long id,
        String title,
        @Nullable String description,
        LocalDateTime updatedAt,
        @Nullable String summary,
        double similarity) {}
