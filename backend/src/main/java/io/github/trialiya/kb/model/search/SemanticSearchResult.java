package io.github.trialiya.kb.model.search;

import java.time.LocalDateTime;

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
        String id, String title, String description, LocalDateTime updatedAt, double similarity) {}
