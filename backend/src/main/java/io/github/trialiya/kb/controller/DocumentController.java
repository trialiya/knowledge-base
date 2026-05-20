package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.ReorderRequest;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.service.DocumentExportService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.SemanticSearchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final DocumentExportService documentExportService;
    private final SemanticSearchService semanticSearchService;

    // ── Tree ──────────────────────────────────────────────────────────────────

    /** Full recursive tree (legacy, kept for backward compat). */
    @GetMapping("/documents/tree")
    public List<DocumentNode> getTree() {
        return service.getTree();
    }

    /**
     * Lazy-load one level of children.
     *
     * <pre>
     * GET /api/documents/children          → root nodes
     * GET /api/documents/children?parentId=42  → children of node 42
     * </pre>
     *
     * Each node includes {@code hasChildren} so the UI knows whether to show a chevron.
     */
    @GetMapping("/documents/children")
    public List<DocumentNode> getChildren(@RequestParam(required = false) String parentId) {
        Long pid = parentId != null ? Long.parseLong(parentId) : null;
        return service.getChildren(pid);
    }

    /**
     * Returns the ancestor IDs from root down to (but not including) the given node. Used by the UI
     * on direct-link open to expand the correct branch in the tree.
     *
     * <pre>GET /api/documents/{id}/ancestors → ["1", "7", "42"]</pre>
     */
    @GetMapping("/documents/{id}/ancestors")
    public List<String> getAncestors(@PathVariable String id) {
        return service.getAncestorIds(Long.parseLong(id));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Document createDocument(@RequestBody CreateDocumentRequest request) {
        return service.create(request);
    }

    @PutMapping("/documents/{id}")
    public Document updateDocument(
            @PathVariable String id, @RequestBody UpdateDocumentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id) {
        service.delete(id);
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    /**
     * Updates the display order of siblings in a folder (or at root level).
     *
     * <pre>
     * PATCH /api/documents/reorder
     * {
     *   "parentId": "42",          // null → root
     *   "orderedIds": ["7","3","1"]
     * }
     * </pre>
     */
    @PatchMapping("/documents/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@RequestBody ReorderRequest request) {
        service.reorder(request);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Unified search endpoint.
     *
     * <pre>
     * GET /api/search?q=...                   → keyword (default)
     * GET /api/search?q=...&mode=semantic     → vector search
     * GET /api/search?q=...&mode=hybrid       → keyword + semantic combined
     * GET /api/search?q=...&mode=hybrid&threshold=0.2&limit=10&kwWeight=0.4&semWeight=0.6
     * </pre>
     *
     * <p>{@code kwWeight} and {@code semWeight} are only used in hybrid mode.
     */
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "keyword") String mode,
            @RequestParam(required = false) Double threshold,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Double kwWeight,
            @RequestParam(required = false) Double semWeight) {

        return switch (mode.toLowerCase()) {
            case "semantic" -> service.semanticSearch(q, threshold, limit);
            case "hybrid" -> service.hybridSearch(q, threshold, limit, kwWeight, semWeight);
            default -> service.search(q);
        };
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    /**
     * Triggers a full reindex of all documents.
     *
     * <pre>POST /api/documents/reindex</pre>
     */
    @PostMapping("/documents/reindex")
    public ReindexResponse reindex() {
        int count = semanticSearchService.reindexAll();
        return new ReindexResponse(count);
    }

    @PostMapping("/documents/admin/export")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void export() {
        documentExportService.exportAll();
    }

    public record ReindexResponse(int indexed) {}
}
