package io.github.trialiya.kb.model.phrase.dto;

import io.github.trialiya.kb.model.phrase.entity.PhraseEntity;

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

    public static Phrase from(PhraseEntity e) {
        return new Phrase(
                e.id(), e.category(), e.label(), e.text(), e.position(), e.enabled(), e.favorite());
    }
}
