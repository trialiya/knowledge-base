package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.phrase.dto.MovePhraseRequest;
import io.github.trialiya.kb.model.phrase.dto.Phrase;
import io.github.trialiya.kb.model.phrase.dto.PhraseRequest;
import io.github.trialiya.kb.model.phrase.entity.PhraseEntity;
import io.github.trialiya.kb.repository.PhraseRepository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the phrase library. Trimming and non-blank validation live here (source of
 * truth), so the controller stays thin and no jakarta.validation dependency is required.
 *
 * <p>{@code NoSuchElementException} (unknown id) and {@code IllegalArgumentException} (blank field)
 * are expected to be mapped to 404/400 by the existing {@code GlobalExceptionHandler}.
 */
@Service
public class PhraseService {

    private final PhraseRepository repository;

    public PhraseService(PhraseRepository repository) {
        this.repository = repository;
    }

    // ── Read ──────────────────────────────────────────────────────────────────────

    /** Chat-facing list: enabled phrases only. */
    public List<Phrase> listEnabled() {
        return repository.findEnabledOrdered().stream().map(Phrase::from).toList();
    }

    /** Admin list: all phrases including disabled. */
    public List<Phrase> listAll() {
        return repository.findAllOrdered().stream().map(Phrase::from).toList();
    }

    /** Quick search by label. Blank query falls back to the full admin list. */
    public List<Phrase> search(@Nullable String q) {
        String needle = q == null ? "" : q.strip();
        if (needle.isEmpty()) return listAll();
        return repository.searchByLabel(needle).stream().map(Phrase::from).toList();
    }

    // ── Write ─────────────────────────────────────────────────────────────────────

    @Transactional
    public Phrase create(PhraseRequest req) {
        String category = required(req.category(), "category");
        String label = required(req.label(), "label");
        String text = required(req.text(), "text");
        boolean enabled = req.enabled() == null || req.enabled();

        int position = repository.findMaxPosition(category) + 1; // append within category
        Instant now = Instant.now();
        PhraseEntity saved =
                repository.save(
                        new PhraseEntity(
                                null, category, label, text, position, enabled, false, now, now));
        return Phrase.from(saved);
    }

    @Transactional
    public Phrase update(Long id, PhraseRequest req) {
        PhraseEntity existing = get(id);
        String category = required(req.category(), "category");
        String label = required(req.label(), "label");
        String text = required(req.text(), "text");
        boolean enabled = req.enabled() == null || req.enabled();

        // Keep the slot when the category is unchanged; otherwise drop the phrase at the end of the
        // new category so positions stay meaningful. Gaps in the old category are tolerated.
        int position = existing.position();
        if (!category.equals(existing.category())) {
            position = repository.findMaxPosition(category) + 1;
        }

        PhraseEntity updated =
                new PhraseEntity(
                        existing.id(),
                        category,
                        label,
                        text,
                        position,
                        enabled,
                        existing.favorite(),
                        existing.createdAt(),
                        Instant.now());
        return Phrase.from(repository.save(updated));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public Phrase setFavorite(Long id, boolean favorite) {
        return Phrase.from(repository.save(get(id).withFavorite(favorite)));
    }

    /** Flip just the {@code enabled} flag — no full rewrite, so concurrent text edits survive. */
    @Transactional
    public Phrase setEnabled(Long id, boolean enabled) {
        return Phrase.from(repository.save(get(id).withEnabled(enabled)));
    }

    /**
     * Reorder a phrase within its own category to {@code target.position()} — a real sibling slot.
     * Uses the windowed-shift pattern: no full renumber, gaps tolerated.
     */
    @Transactional
    public Phrase move(Long id, MovePhraseRequest target) {
        PhraseEntity phrase = get(id);
        int oldPos = phrase.position();
        int newPos = target.position();
        if (newPos == oldPos) return Phrase.from(phrase);

        if (newPos < oldPos) {
            repository.shiftWindowUp(phrase.category(), newPos, oldPos);
        } else {
            repository.shiftWindowDown(phrase.category(), oldPos, newPos);
        }
        return Phrase.from(repository.save(phrase.withPosition(newPos)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private PhraseEntity get(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("Phrase not found: " + id));
    }

    private static String required(String value, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' must not be blank");
        }
        return trimmed;
    }
}
