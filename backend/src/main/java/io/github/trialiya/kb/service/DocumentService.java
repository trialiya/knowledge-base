package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository repo;

    // ── Tree ────────────────────────────────────────────────────────────────

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

    // ── CRUD ────────────────────────────────────────────────────────────────

    public Document create(CreateDocumentRequest req) {
        String type =
                (req.getType() != null && req.getType().equals("folder")) ? "folder" : "document";
        DocumentEntity entity =
                new DocumentEntity(
                        null,
                        req.getTitle(),
                        type,
                        req.getParentId() == null ? null : Long.parseLong(req.getParentId()),
                        req.getDescription(),
                        LocalDateTime.now());
        return toDto(repo.save(entity));
    }

    public Document update(String id, UpdateDocumentRequest req) {
        DocumentEntity existing = findOrThrow(id);

        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            existing.setTitle(req.getTitle());
        }
        if (req.getDescription() != null) {
            existing.setDescription(req.getDescription());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return toDto(repo.save(existing));
    }

    public void delete(String id) {
        findOrThrow(id);
        List<Long> ids = repo.findDescendantIds(Long.parseLong(id));
        repo.deleteAllById(ids);
    }

    // ── Search ──────────────────────────────────────────────────────────────

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

    // ── Helpers ─────────────────────────────────────────────────────────────

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
}
