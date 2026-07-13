package io.github.trialiya.kb.model.phrase.dto;

import org.jspecify.annotations.Nullable;

/**
 * Create/update body. {@code category}, {@code label}, {@code text} are required and are trimmed
 * and checked in {@code PhraseService} (no jakarta.validation dependency assumed). {@code enabled}
 * is optional: {@code null} is treated as {@code true}. The favourite flag is not part of this body
 * — it is toggled via its own endpoint.
 */
public record PhraseRequest(
        String category, String label, String text, @Nullable Boolean enabled) {}
