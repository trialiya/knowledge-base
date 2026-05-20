package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository repo;
    private final SemanticSearchService semanticSearchService;

    // ── Tree ─────────────────────────────────────────────────────────────────

    public List<DocumentNode> getTree() {
        List<DocumentEntity> roots = repo.findRoots();
        Map<Long, List<DocumentEntity>> byParent = new HashMap<>();
        repo.findAll()
                .forEach(
                        e -> {
                            if (e.getParentId() != null) {
                                byParent.computeIfAbsent(e.getParentId(), k -> new ArrayList<>())
                                        .add(e);
                            }
                        });
        return roots.stream().map(r -> buildNode(r, byParent)).collect(Collectors.toList());
    }

    private DocumentNode buildNode(DocumentEntity e, Map<Long, List<DocumentEntity>> byParent) {
        List<DocumentNode> children =
                byParent.getOrDefault(e.getId(), Collections.emptyList()).stream()
                        .map(child -> buildNode(child, byParent))
                        .collect(Collectors.toList());
        return new DocumentNode(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                e.getDescription(),
                e.getUpdatedAt(),
                children);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public Document create(CreateDocumentRequest req) {
        String type = "folder".equals(req.getType()) ? "folder" : "document";
        DocumentEntity entity =
                new DocumentEntity(
                        null,
                        req.getTitle(),
                        type,
                        req.getParentId() == null ? null : Long.parseLong(req.getParentId()),
                        req.getDescription(),
                        LocalDateTime.now());
        DocumentEntity saved = repo.save(entity);

        // Index embedding asynchronously-ish; failure must not roll back the save
        tryIndex(saved.getId(), saved.getTitle(), saved.getDescription());

        return toDto(saved);
    }

    @Transactional
    public Document update(String id, UpdateDocumentRequest req) {
        DocumentEntity existing = findOrThrow(id);

        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            existing.setTitle(req.getTitle());
        }
        if (req.getDescription() != null) {
            existing.setDescription(req.getDescription());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        DocumentEntity saved = repo.save(existing);

        tryIndex(saved.getId(), saved.getTitle(), saved.getDescription());

        return toDto(saved);
    }

    @Transactional
    public void delete(String id) {
        findOrThrow(id);
        List<Long> ids = repo.findDescendantIds(Long.parseLong(id));
        repo.deleteAllById(ids);
        // Embeddings are removed via ON DELETE CASCADE in the DB, but we also
        // clean up explicitly in case the cascade is not available in the env.
        ids.forEach(
                docId -> {
                    try {
                        semanticSearchService.deleteIndex(docId);
                    } catch (Exception ex) {
                        log.warn(
                                "Could not remove embedding for document id={}: {}",
                                docId,
                                ex.getMessage());
                    }
                });
    }

    // ── Keyword search (legacy) ───────────────────────────────────────────────

    public List<SearchResult> search(String q) {
        return repo.search(q).stream()
                .map(
                        e ->
                                new SearchResult(
                                        String.valueOf(e.getId()),
                                        e.getTitle(),
                                        generateSnippet(e.getDescription(), q.toLowerCase()),
                                        e.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    // ── Semantic search ───────────────────────────────────────────────────────

    /**
     * Runs a semantic (vector) search and maps results to the common {@link SearchResult} DTO so
     * the controller stays unchanged.
     *
     * @param q natural-language query
     * @param threshold cosine-similarity cutoff (0–1); pass {@code null} for default
     * @param limit max results; pass {@code null} for default
     */
    public List<SearchResult> semanticSearch(String q, Double threshold, Integer limit) {
        List<SemanticSearchResult> raw =
                (threshold != null || limit != null)
                        ? semanticSearchService.search(
                                q, threshold != null ? threshold : 0.25, limit != null ? limit : 20)
                        : semanticSearchService.search(q);

        return raw.stream()
                .map(
                        r ->
                                new SearchResult(
                                        r.id(),
                                        r.title(),
                                        generateSnippet(r.description(), q.toLowerCase()),
                                        r.updatedAt()))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DocumentEntity findOrThrow(String id) {
        return repo.findById(Long.parseLong(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Document toDto(DocumentEntity e) {
        return new Document(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                e.getDescription(),
                e.getUpdatedAt(),
                null);
    }

    private String generateSnippet(String content, String query) {
        if (content == null) return "";
        int idx = content.toLowerCase().indexOf(query);
        if (idx == -1) return content.substring(0, Math.min(150, content.length())) + "...";
        int start = Math.max(0, idx - 50);
        int end = Math.min(content.length(), idx + 100);
        return (start > 0 ? "..." : "")
                + content.substring(start, end)
                + (end < content.length() ? "..." : "");
    }

    /**
     * Tries to index the document; logs a warning but never throws, so that an OpenAI outage cannot
     * break document CRUD operations.
     */
    private void tryIndex(Long id, String title, String description) {
        try {
            semanticSearchService.indexDocument(id, title, description);
        } catch (Exception ex) {
            log.warn("Embedding index failed for document id={}: {}", id, ex.getMessage());
        }
    }
}
