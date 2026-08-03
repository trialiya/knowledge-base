package io.github.trialiya.kb.model.phrase.dto;

import io.github.trialiya.kb.model.phrase.entity.PhraseEntity;
import java.util.Objects;

/**
 * API view of a phrase. Carries {@code favorite}; the admin list also relies on {@code enabled}.
 */
public record Phrase(
        Long id,
        String category,
        String label,
        String text,
        int position,
        boolean enabled,
        boolean favorite) {

    /** {@code e} is always the result of a save/fetch, so its id is always assigned. */
    public static Phrase from(PhraseEntity e) {
        return new Phrase(
                Objects.requireNonNull(e.id()),
                e.category(),
                e.label(),
                e.text(),
                e.position(),
                e.enabled(),
                e.favorite());
    }
}
