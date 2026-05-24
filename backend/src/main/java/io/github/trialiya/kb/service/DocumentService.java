package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.PagedChildren;
import io.github.trialiya.kb.model.doc.dto.ReorderRequest;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class DocumentService {

    private final DocumentRepository repo;
    private final SemanticSearchService semanticSearchService;
    private final SearchConfiguration searchConfig;

    public DocumentService(
            DocumentRepository repo,
            SemanticSearchService semanticSearchService,
            SearchConfiguration searchConfig) {
        this.repo = repo;
        this.semanticSearchService = semanticSearchService;
        this.searchConfig = searchConfig;
    }

    // ── Tree ─────────────────────────────────────────────────────────────────

    /** Full recursive tree (kept for backward compat, not used by UI anymore). */
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
        byParent.values()
                .forEach(list -> list.sort(Comparator.comparingInt(DocumentEntity::getPosition)));

        return roots.stream().map(r -> buildNode(r, byParent)).collect(Collectors.toList());
    }

    /**
     * Finds documents/folders whose title contains {@code name} (case-insensitive). Exact matches
     * are returned first; partial matches follow ordered by title length. Each result includes full
     * description and direct children count via {@code hasChildren}.
     *
     * @param name full or partial title to look up
     * @return list of matching nodes (up to 20), never null
     */
    public List<DocumentNode> findByName(String name) {
        return repo.findByTitleContaining(name).stream()
                .map(this::toStubNode)
                .collect(Collectors.toList());
    }

    public DocumentNode getById(Long id) {
        return repo.findById(id).map(this::toShallowNode).orElse(null);
    }

    /**
     * Returns ancestor IDs from root down to (but not including) the given node. e.g. node at depth
     * 3 → [rootId, folderId, parentFolderId]. Empty list for root-level nodes.
     */
    public List<String> getAncestorIds(Long id) {
        return repo.findAncestorIds(id).stream().map(String::valueOf).collect(Collectors.toList());
    }

    /**
     * Returns one page of children for a given parent (null = root), using Spring's {@link
     * Pageable}. Each node includes {@code hasChildren} so the UI can show a chevron without
     * loading the next level eagerly.
     */
    public PagedChildren getChildrenPaged(Long parentId, Pageable pageable) {
        Page<DocumentEntity> page =
                parentId == null
                        ? repo.findByParentIdIsNull(pageable)
                        : repo.findByParentId(parentId, pageable);
        Page<DocumentNode> mapped = page.map(this::toStubNode);
        return PagedChildren.from(mapped);
    }

    /**
     * Returns ALL children for a given parent (null = root), unpaged. Kept for backward compat (AI
     * tools, reorder, etc.).
     */
    public List<DocumentNode> getChildren(Long parentId) {
        List<DocumentEntity> items =
                parentId == null ? repo.findRoots() : repo.findByParentId(parentId);
        return items.stream().map(this::toStubNode).collect(Collectors.toList());
    }

    /**
     * Flat tree skeleton: only id + title + type + parentId + hasChildren. Used by the AI tool so
     * the model gets the full structure without the heavy description content.
     */
    public List<DocumentNode> getTreeSkeleton() {
        Set<Long> parentIds = repo.findAllParentIds();
        return StreamSupport.stream(repo.findAll().spliterator(), false)
                .map(
                        e ->
                                new DocumentNode(
                                        String.valueOf(e.getId()),
                                        e.getTitle(),
                                        e.getType(),
                                        e.getParentId() == null
                                                ? null
                                                : String.valueOf(e.getParentId()),
                                        null,
                                        null,
                                        Collections.emptyList(),
                                        parentIds.contains(e.getId()),
                                        e.isSystem()))
                .collect(Collectors.toList());
    }

    /** Full shallow node: entity + its direct children (used by getById). */
    private DocumentNode toShallowNode(DocumentEntity e) {
        List<DocumentNode> children =
                repo.findByParentId(e.getId()).stream()
                        .map(
                                c ->
                                        new DocumentNode(
                                                String.valueOf(c.getId()),
                                                c.getTitle(),
                                                c.getType(),
                                                String.valueOf(c.getParentId()),
                                                null,
                                                null,
                                                Collections.emptyList(),
                                                repo.hasChildren(c.getId()),
                                                c.isSystem()))
                        .collect(Collectors.toList());
        return new DocumentNode(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                e.getDescription(),
                e.getUpdatedAt(),
                children,
                !children.isEmpty(),
                e.isSystem());
    }

    /**
     * Stub node for tree/children listing. Description is truncated to {@value #SNIPPET_LENGTH}
     * characters so ContentsTable can show a preview without transferring the full content. Use
     * {@link #getById(Long)} for the complete document.
     */
    private static final int SNIPPET_LENGTH = 150;

    private DocumentNode toStubNode(DocumentEntity e) {
        boolean hc = repo.hasChildren(e.getId());
        return new DocumentNode(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                snippetOf(e.getDescription()),
                e.getUpdatedAt(),
                Collections.emptyList(),
                hc,
                e.isSystem());
    }

    /** Returns the first {@value #SNIPPET_LENGTH} characters of {@code text}, or null. */
    private static String snippetOf(String text) {
        if (text == null || text.isBlank()) return null;
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH);
    }

    private DocumentNode buildNode(DocumentEntity e, Map<Long, List<DocumentEntity>> byParent) {
        List<DocumentNode> children =
                byParent.getOrDefault(e.getId(), Collections.emptyList()).stream()
                        .map(child -> buildNode(child, byParent))
                        .collect(Collectors.toList());
        boolean hc = !children.isEmpty() || repo.hasChildren(e.getId());
        return new DocumentNode(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                null, // description omitted — fetch via GET /api/documents/{id}
                e.getUpdatedAt(),
                children,
                hc,
                e.isSystem());
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public Document create(CreateDocumentRequest req) {
        String type = "folder".equals(req.getType()) ? "folder" : "document";
        int nextPos =
                nextSiblingPosition(
                        req.getParentId() == null ? null : Long.parseLong(req.getParentId()));

        DocumentEntity entity =
                new DocumentEntity(
                        null,
                        req.getTitle(),
                        type,
                        req.getParentId() == null ? null : Long.parseLong(req.getParentId()),
                        req.getDescription(),
                        LocalDateTime.now(),
                        nextPos,
                        false); // новые узлы никогда не системные
        DocumentEntity saved = repo.save(entity);

        tryIndex(saved.getId(), saved.getTitle(), saved.getDescription());
        return toDto(saved);
    }

    @Transactional
    public Document update(String id, UpdateDocumentRequest req) {
        DocumentEntity existing = findOrThrow(id);

        // Системный узел: разрешаем менять только description
        if (existing.isSystem()) {
            if (req.getTitle() != null && !req.getTitle().equals(existing.getTitle())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Cannot rename a system document");
            }
        } else {
            if (req.getTitle() != null && !req.getTitle().isBlank()) {
                existing.setTitle(req.getTitle());
            }
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
        DocumentEntity entity = findOrThrow(id);
        if (entity.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Cannot delete a system document");
        }
        List<Long> ids = repo.findDescendantIds(Long.parseLong(id));
        repo.deleteAllById(ids);
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

    // ── Reorder ───────────────────────────────────────────────────────────────

    /**
     * Reassigns {@code position} for a group of siblings. System nodes are excluded from reordering
     * silently — their position stays fixed.
     */
    @Transactional
    public void reorder(ReorderRequest req) {
        List<String> ids = req.getOrderedIds();
        if (ids == null || ids.isEmpty()) return;

        // Build id→position map, excluding system nodes in one query
        List<Long> longIds = ids.stream().map(Long::parseLong).collect(Collectors.toList());
        Set<Long> systemIds = repo.findSystemIdsByIdIn(longIds);

        Map<Long, Integer> positionMap = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Long nodeId = Long.parseLong(ids.get(i));
            if (!systemIds.contains(nodeId)) {
                positionMap.put(nodeId, i);
            }
        }

        if (!positionMap.isEmpty()) {
            repo.batchUpdatePositions(positionMap);
        }
    }

    // ── Keyword search ────────────────────────────────────────────────────────

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
        double t = threshold != null ? threshold : searchConfig.semantic().threshold();
        int l = limit != null ? limit : searchConfig.semantic().limit();

        return semanticSearchService.search(q, t, l).stream()
                .map(
                        r ->
                                new SearchResult(
                                        r.id(),
                                        r.title(),
                                        generateSnippet(r.description(), q.toLowerCase()),
                                        r.updatedAt()))
                .collect(Collectors.toList());
    }

    // ── Hybrid search ─────────────────────────────────────────────────────────

    /**
     * Combines keyword and semantic results using configurable weights.
     *
     * <p>Algorithm:
     *
     * <ol>
     *   <li>Collect keyword hits (normalised score = rank position inverted over result count).
     *   <li>Collect semantic hits (score = cosine similarity, already in 0..1).
     *   <li>Merge by document id: {@code hybridScore = kw * keywordWeight + sem * semanticWeight}.
     *   <li>Sort descending, return top {@code limit}.
     * </ol>
     *
     * @param q search query
     * @param threshold min semantic similarity; {@code null} → from config
     * @param limit max results; {@code null} → from config
     * @param kwWeight keyword weight 0..1; {@code null} → from config
     * @param semWeight semantic weight 0..1; {@code null} → from config
     */
    public List<SearchResult> hybridSearch(
            String q, Double threshold, Integer limit, Double kwWeight, Double semWeight) {

        SearchConfiguration.HybridConfig cfg = searchConfig.hybrid();
        double kw = kwWeight != null ? kwWeight : cfg.keywordWeight();
        double sem = semWeight != null ? semWeight : cfg.semanticWeight();
        double thr = threshold != null ? threshold : cfg.threshold();
        int lim = limit != null ? limit : cfg.limit();

        // ── 1. Keyword hits ───────────────────────────────────────────────────
        List<SearchResult> kwResults = search(q);
        Map<String, Double> kwScores = new LinkedHashMap<>();
        int kwSize = kwResults.size();
        for (int i = 0; i < kwSize; i++) {
            // Rank-based score: best hit = 1.0, worst = 1/n
            kwScores.put(kwResults.get(i).getId(), (double) (kwSize - i) / kwSize);
        }

        // ── 2. Semantic hits ──────────────────────────────────────────────────
        List<SemanticSearchResult> semResults =
                semanticSearchService.search(q, thr, searchConfig.semantic().limit());
        Map<String, Double> semScores = new LinkedHashMap<>();
        for (SemanticSearchResult r : semResults) {
            semScores.put(r.id(), r.similarity());
        }

        // ── 3. Build unified candidate set ────────────────────────────────────
        Map<String, SearchResult> snippets = new HashMap<>();
        for (SearchResult sr : kwResults) {
            snippets.put(sr.getId(), sr);
        }
        for (SemanticSearchResult sr : semResults) {
            snippets.computeIfAbsent(
                    sr.id(),
                    id ->
                            new SearchResult(
                                    id,
                                    sr.title(),
                                    generateSnippet(sr.description(), q.toLowerCase()),
                                    sr.updatedAt()));
        }

        // ── 4. Combine scores & sort ──────────────────────────────────────────
        return snippets.keySet().stream()
                .map(
                        id -> {
                            double score =
                                    kw * kwScores.getOrDefault(id, 0.0)
                                            + sem * semScores.getOrDefault(id, 0.0);
                            return Map.entry(score, snippets.get(id));
                        })
                .sorted(Map.Entry.<Double, SearchResult>comparingByKey().reversed())
                .limit(lim)
                .map(Map.Entry::getValue)
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
                null, // description omitted — fetch via GET /api/documents/{id}
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

    private void tryIndex(Long id, String title, String description) {
        try {
            semanticSearchService.indexDocument(id, title, description);
        } catch (Exception ex) {
            log.warn("Embedding index failed for document id={}: {}", id, ex.getMessage());
        }
    }

    private int nextSiblingPosition(Long parentId) {
        List<DocumentEntity> siblings =
                parentId == null ? repo.findRoots() : repo.findByParentId(parentId);
        return siblings.stream().mapToInt(DocumentEntity::getPosition).max().orElse(-1) + 1;
    }
}
