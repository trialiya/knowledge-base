package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentHistory;
import io.github.trialiya.kb.model.doc.dto.DocumentHistoryShort;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.MoveRequest;
import io.github.trialiya.kb.model.doc.dto.PagedChildren;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.service.DocumentExportService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.SemanticSearchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final DocumentExportService documentExportService;
    private final SemanticSearchService semanticSearchService;

    // ── Tree ──────────────────────────────────────────────────────────────────

    /**
     * Lazy-load one page of children with Spring Pageable.
     *
     * <pre>
     * GET /api/documents/children                          → root nodes, page 0, size 10
     * GET /api/documents/children?parentId=42              → children of 42, page 0, size 10
     * GET /api/documents/children?parentId=42&page=1&size=10 → second page
     * </pre>
     *
     * Response is {@link PagedChildren} with totalElements, totalPages, hasNext, etc.
     */
    @GetMapping("/children")
    public PagedChildren getChildren(
            @RequestParam(required = false) @Nullable Long parentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("position"));
        return service.getChildrenPaged(parentId, pageable);
    }

    /**
     * Returns the ancestor IDs from root down to (but not including) the given node. Used by the UI
     * on direct-link open to expand the correct branch in the tree.
     *
     * <pre>GET /api/documents/{id}/ancestors → ["1", "7", "42"]</pre>
     */
    @GetMapping("/{id}/ancestors")
    public List<Long> getAncestors(@PathVariable long id) {
        return service.getAncestorIds(id);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Document createDocument(@RequestBody CreateDocumentRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public DocumentNode getDocument(@PathVariable long id) {
        DocumentNode node = service.getById(id);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return node;
    }

    /** История изменений описания документа (newest-first). */
    @GetMapping("/{id}/history")
    public List<DocumentHistoryShort> getHistory(@PathVariable long id) {
        return service.getDescriptionHistory(id);
    }

    @GetMapping("/{id}/history/{version}")
    public DocumentHistory getHistoryVersion(@PathVariable long id, @PathVariable int version) {
        return service.getHistoryVersion(id, version);
    }

    @PutMapping("/{id}")
    public Document updateDocument(
            @PathVariable long id, @RequestBody UpdateDocumentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable long id) {
        service.delete(id);
    }

    /**
     * Generates (or regenerates) an AI summary for the document description and persists it.
     *
     * <p>The summary is always generated on demand — it is never updated automatically. After the
     * call, {@code summaryStale} in the response will be {@code false}. It will become {@code true}
     * again the next time the description is saved with different content.
     *
     * <pre>POST /api/documents/{id}/summarize</pre>
     *
     * @return the updated {@link DocumentNode} with {@code summary}, {@code summaryStale = false},
     *     and {@code summarySourceVersion} reflecting the current description version
     * @throws ResponseStatusException 404 document not found
     * @throws ResponseStatusException 422 document has no description to summarise
     * @throws ResponseStatusException 409 concurrent modification detected
     */
    @PostMapping("/{id}/summarize")
    public DocumentNode summarizeDocument(@PathVariable long id) {
        return service.summarize(id);
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    /**
     * Moves a document/folder to a target parent AND a specific slot in one call.
     *
     * <pre>
     * PATCH /api/documents/{id}/move
     * { "parentId": 1, "afterId": 7 }     // into folder 1, right after node 7
     * { "parentId": 1, "afterId": null }  // into folder 1, first
     * { "parentId": null, "afterId": 42 } // to root level, right after 42
     * </pre>
     *
     * Replaces the moveToParent + reorder pair for drag-and-drop: the client names ONE neighbour
     * instead of sending the whole sibling order, so a lazily-loaded tree can never produce a
     * corrupt order. Responds 400 for cycles or {@code afterId == id}, 403 for system nodes, 404 if
     * any referenced node is missing, 409 on concurrent modification, 422 if the target is not a
     * folder or {@code afterId} belongs to another level.
     */
    @PatchMapping("/{id}/move")
    public Document move(@PathVariable long id, @RequestBody MoveRequest request) {
        return service.move(id, request.parentId(), request.afterId());
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Unified search endpoint.
     *
     * <pre>
     * GET /api/documents/search?q=...                   → keyword (default)
     * GET /api/documents/search?q=...&mode=semantic     → vector search
     * GET /api/documents/search?q=...&mode=hybrid       → keyword + semantic combined
     * GET /api/documents/search?q=...&mode=hybrid&threshold=0.2&limit=10&kwWeight=0.4&semWeight=0.6
     * </pre>
     *
     * <p>{@code kwWeight} and {@code semWeight} are only used in hybrid mode.
     */
    @GetMapping("/search")
    public List<SearchResult> searchDocuments(
            @RequestParam String q,
            @RequestParam(defaultValue = "keyword") String mode,
            @RequestParam(required = false) @Nullable Double threshold,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable Double kwWeight,
            @RequestParam(required = false) @Nullable Double semWeight) {

        return switch (mode.toLowerCase()) {
            case "semantic" -> service.semanticSearch(q, threshold, limit);
            case "hybrid" -> service.hybridSearch(q, threshold, limit, kwWeight, semWeight);
            default -> service.search(q);
        };
    }

    // ── @mention autocomplete ─────────────────────────────────────────────────────

    /**
     * Find documents/folders whose title contains {@code name} (case-insensitive). Used by the
     * markdown editor's {@code @mention} autocomplete.
     *
     * <p>Exact matches are returned first; partial matches follow ordered by title length.
     *
     * <pre>GET /api/documents/search-by-name?name=введение&limit=10</pre>
     *
     * @param name full or partial title fragment to look up (required, min 1 char)
     * @param limit max results to return (default 10, max 20)
     */
    @GetMapping("/search-by-name")
    public List<DocumentNode> searchByName(
            @RequestParam String name, @RequestParam(defaultValue = "10") int limit) {
        if (name == null || name.isBlank()) return List.of();
        return service.findByName(name).stream().limit(Math.min(limit, 20)).toList();
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    /**
     * Triggers a full reindex of all documents.
     *
     * <pre>POST /api/documents/admin/reindex</pre>
     */
    @PostMapping("/admin/reindex")
    public ReindexResponse reindex() {
        int count = semanticSearchService.reindexAll();
        return new ReindexResponse(count);
    }

    @PostMapping("/admin/export")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void export(@RequestParam(defaultValue = "true") boolean meta) {
        documentExportService.exportAll(meta);
    }

    public record ReindexResponse(int indexed) {}
}
