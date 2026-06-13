package io.github.trialiya.kb.model.phrase.entity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Phrase library row. Global by design — the app is single-user and the library is shared, so there
 * is no user binding. {@code category} is a free-form string (no separate category catalogue);
 * {@code position} orders phrases within a category. Immutable record: mutations produce a copy and
 * are persisted via {@code save}.
 */
@Table("phrase")
public record PhraseEntity(
        @Id Long id,
        String category,
        String label,
        String text,
        int position,
        boolean enabled,
        boolean favorite,
        Instant createdAt,
        Instant updatedAt) {

    /** Copy with the enabled flag flipped/set. */
    public PhraseEntity withEnabled(boolean en) {
        return new PhraseEntity(
                id, category, label, text, position, en, favorite, createdAt, Instant.now());
    }

    /** Copy at a new position within the same category. */
    public PhraseEntity withPosition(int newPosition) {
        return new PhraseEntity(
                id,
                category,
                label,
                text,
                newPosition,
                enabled,
                favorite,
                createdAt,
                Instant.now());
    }

    /** Copy with the favourite flag flipped/set. */
    public PhraseEntity withFavorite(boolean fav) {
        return new PhraseEntity(
                id, category, label, text, position, enabled, fav, createdAt, Instant.now());
    }
}
