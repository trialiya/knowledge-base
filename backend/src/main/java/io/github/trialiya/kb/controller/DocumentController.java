package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
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
    private final SemanticSearchService semanticSearchService;

    // ── Tree ──────────────────────────────────────────────────────────────────

    @GetMapping("/documents/tree")
    public List<DocumentNode> getTree() {
        return service.getTree();
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

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Unified search endpoint.
     *
     * <pre>
     * GET /api/search?q=...                  → keyword (default / hybrid)
     * GET /api/search?q=...&mode=semantic    → vector search (cosine similarity)
     * GET /api/search?q=...&mode=semantic&threshold=0.5&limit=10
     * </pre>
     */
    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "keyword") String mode,
            @RequestParam(required = false) Double threshold,
            @RequestParam(required = false) Integer limit) {

        return switch (mode.toLowerCase()) {
            case "semantic" -> service.semanticSearch(q, threshold, limit);
            default -> service.search(q);
        };
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    /**
     * Triggers a full reindex of all documents. Useful after switching embedding models or on first
     * deployment.
     *
     * <pre>POST /api/documents/reindex</pre>
     */
    @PostMapping("/documents/reindex")
    public ReindexResponse reindex() {
        int count = semanticSearchService.reindexAll();
        return new ReindexResponse(count);
    }

    public record ReindexResponse(int indexed) {}
}
