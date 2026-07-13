package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.phrase.dto.MovePhraseRequest;
import io.github.trialiya.kb.model.phrase.dto.Phrase;
import io.github.trialiya.kb.model.phrase.dto.PhraseRequest;
import io.github.trialiya.kb.service.PhraseService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phrase library endpoints.
 *
 * <p><b>Public</b> ({@code /api/phrases}) backs the empty-chat phrase block and returns only
 * enabled phrases. The favourite toggle is public too: the app is single-user, so "favourite" is a
 * global personalisation that can be set from chat or admin.
 *
 * <p><b>Admin</b> ({@code /api/admin/phrases}) is the full CRUD + reorder surface and sees disabled
 * phrases as well. {@code GET ?q=} doubles as the quick search by label.
 */
@RestController
public class PhraseController {

    private final PhraseService service;

    public PhraseController(PhraseService service) {
        this.service = service;
    }

    // ── Public (chat) ──────────────────────────────────────────────────────────────

    @GetMapping("/api/phrases")
    public List<Phrase> list() {
        return service.listEnabled();
    }

    /** Toggle favourite from the chat phrase block (single-user, global flag). */
    @PatchMapping("/api/phrases/{id}/favorite")
    public Phrase favorite(@PathVariable Long id, @RequestParam boolean value) {
        return service.setFavorite(id, value);
    }

    // ── Admin (settings) ─────────────────────────────────────────────────────────────

    /** Full list, or quick search by label when {@code q} is present. */
    @GetMapping("/api/admin/phrases")
    public List<Phrase> listAll(@RequestParam(required = false) @Nullable String q) {
        return service.search(q);
    }

    @PostMapping("/api/admin/phrases")
    public Phrase create(@RequestBody PhraseRequest request) {
        return service.create(request);
    }

    @PutMapping("/api/admin/phrases/{id}")
    public Phrase update(@PathVariable Long id, @RequestBody PhraseRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/admin/phrases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PatchMapping("/api/admin/phrases/{id}/favorite")
    public Phrase adminFavorite(@PathVariable Long id, @RequestParam boolean value) {
        return service.setFavorite(id, value);
    }

    /** Toggle the {@code enabled} flag alone — avoids a full PUT just to show/hide a phrase. */
    @PatchMapping("/api/admin/phrases/{id}/enabled")
    public Phrase adminEnabled(@PathVariable Long id, @RequestParam boolean value) {
        return service.setEnabled(id, value);
    }

    /** Reorder within the phrase's category; body carries the target sibling position. */
    @PatchMapping("/api/admin/phrases/{id}/move")
    public Phrase move(@PathVariable Long id, @RequestBody MovePhraseRequest request) {
        return service.move(id, request);
    }
}
