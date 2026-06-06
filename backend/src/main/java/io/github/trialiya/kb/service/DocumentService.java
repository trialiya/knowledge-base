package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentHistory;
import io.github.trialiya.kb.model.doc.dto.DocumentHistoryShort;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.PagedChildren;
import io.github.trialiya.kb.model.doc.dto.ReorderRequest;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentHistoryEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentHistoryShortResult;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentHistoryRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class DocumentService {

    /**
     * Stub node for tree/children listing. Description is truncated to {@value #SNIPPET_LENGTH}
     * characters so ContentsTable can show a preview without transferring the full content. Use
     * {@link #getById(Long)} for the complete document.
     */
    private static final int SNIPPET_LENGTH = 150;

    private final DocumentRepository repo;
    private final DocumentHistoryRepository historyRepo;
    private final DocumentSummaryService documentSummaryService;
    private final SemanticSearchService semanticSearchService;
    private final SearchConfiguration searchConfig;

    public DocumentService(
            DocumentRepository repo,
            DocumentHistoryRepository historyRepo,
            DocumentSummaryService documentSummaryService,
            SemanticSearchService semanticSearchService,
            SearchConfiguration searchConfig) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.documentSummaryService = documentSummaryService;
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
                                        e.isSystem(),
                                        // summary fields omitted in skeleton —
                                        // UI does not need them for tree navigation
                                        null,
                                        false,
                                        null))
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
                                                c.isSystem(),
                                                // children in the list carry their own summary
                                                // state so the UI can show badges in the tree
                                                c.getSummary(),
                                                c.isSummaryStale(),
                                                c.getSummarySourceVersion()))
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
                e.isSystem(),
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
    }

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
                e.isSystem(),
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
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
                e.isSystem(),
                // summary omitted in full tree — same rationale as description
                null,
                false,
                null);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public Document create(CreateDocumentRequest req) {
        String type = "folder".equals(req.getType()) ? "folder" : "document";
        int nextPos = nextSiblingPosition(req.getParentId());

        DocumentEntity entity =
                new DocumentEntity(
                        null,
                        req.getTitle(),
                        type,
                        req.getParentId(),
                        req.getDescription(),
                        LocalDateTime.now(),
                        nextPos,
                        false, // новые узлы никогда не системные
                        0, // version — Spring Data JDBC проставит 1 при INSERT
                        null, // summary — ещё не генерировалось
                        null, // summarySourceVersion
                        1); // descriptionVersion starts at 1
        DocumentEntity saved = repo.save(entity);

        historyRepo.save(snapshotOf(saved));

        tryIndex(saved.getId(), saved.getTitle(), saved.getDescription());
        return toDto(saved);
    }

    /**
     * Updates a document and saves a history snapshot of the previous state.
     *
     * <p>Flow inside the transaction:
     *
     * <ol>
     *   <li>Load current entity (holds current {@code version}).
     *   <li>Write a {@link DocumentHistoryEntity} snapshot of the current state.
     *   <li>Apply the requested changes to the entity.
     *   <li>If {@code description} actually changed, increment {@code descriptionVersion}. This
     *       makes any existing summary stale ({@code summarySourceVersion < descriptionVersion})
     *       without touching the summary itself — the user can still read it and decide whether to
     *       regenerate. Rename / move / reorder do NOT increment {@code descriptionVersion}, so
     *       they never affect summary staleness.
     *   <li>Call {@code repo.save()} — Spring Data JDBC appends {@code AND version = ?} to the
     *       {@code UPDATE}, then increments the column.
     *   <li>If another transaction committed in between, Spring throws {@link
     *       OptimisticLockingFailureException}, which we surface as HTTP 409.
     * </ol>
     *
     * @throws ResponseStatusException 403 if trying to rename a system document
     * @throws ResponseStatusException 404 if the document does not exist
     * @throws ResponseStatusException 409 if a concurrent modification was detected
     */
    @Transactional
    public Document update(long id, UpdateDocumentRequest req) {
        DocumentEntity existing = findOrThrow(id);

        // ── 1. Apply title change ─────────────────────────────────────────────
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

        // ── 2. Apply description change & track descriptionVersion ────────────
        if (req.getDescription() != null) {
            boolean descriptionChanged =
                    !Objects.equals(existing.getDescription(), req.getDescription());
            existing.setDescription(req.getDescription());
            if (descriptionChanged) {
                // Incrementing descriptionVersion is the sole mechanism that marks the
                // summary as stale. The summary itself is not modified here — the user
                // can still read it and choose to regenerate via POST …/summarize.
                existing.setDescriptionVersion(existing.getDescriptionVersion() + 1);
            }
        }

        existing.setUpdatedAt(LocalDateTime.now());

        // ── 3. Save (optimistic lock check happens here) ──────────────────────
        DocumentEntity saved;
        try {
            saved = repo.save(existing);
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document was modified by another request. Please reload and try again.");
        }

        // ── 4. Persist snapshot of current state (always, as before) ──────────
        historyRepo.save(snapshotOf(saved));

        tryIndex(saved.getId(), saved.getTitle(), saved.getDescription());
        return toDto(saved);
    }

    /**
     * Generates an AI summary for the document's description and persists it.
     *
     * <p>The description is sent to the LLM in full — no truncation. {@code summarySourceVersion}
     * is set to the current {@code descriptionVersion}, clearing the stale flag until the
     * description changes again.
     *
     * @param id document id
     * @return updated {@link DocumentNode} with summary fields populated
     * @throws ResponseStatusException 404 if the document does not exist
     * @throws ResponseStatusException 422 if the document has no description to summarise
     * @throws ResponseStatusException 409 on optimistic lock conflict
     */
    @Transactional
    public DocumentNode summarize(long id) {
        DocumentEntity entity = findOrThrow(id);

        if (entity.getDescription() == null || entity.getDescription().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Document has no description to summarise");
        }

        String summaryText = documentSummaryService.summarize(entity);

        entity.setSummary(summaryText);
        entity.setSummarySourceVersion(entity.getDescriptionVersion());
        entity.setUpdatedAt(LocalDateTime.now());

        DocumentEntity saved = repo.save(entity);

        historyRepo.save(snapshotOf(saved));

        log.info("Summarised document id={} title='{}'", id, saved.getTitle());
        return toShallowNode(saved);
    }

    @Transactional
    public void delete(long id) {
        DocumentEntity entity = findOrThrow(id);
        if (entity.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Cannot delete a system document");
        }
        List<Long> ids = repo.findDescendantIds(id);
        // document_history rows are removed automatically via ON DELETE CASCADE
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

    // ── Move to parent ────────────────────────────────────────────────────────

    /**
     * Moves a document/folder to a new parent (or to the root level).
     *
     * <p>Validation:
     *
     * <ol>
     *   <li>The node must exist.
     *   <li>System nodes cannot be moved.
     *   <li>If {@code newParentId} is not null the target folder must exist and be a folder.
     *   <li>Moving a folder into itself or any of its descendants is rejected (cycle check).
     * </ol>
     *
     * After the move the node is appended at the end of the new sibling list.
     *
     * @param id the document/folder to move
     * @param targetParentId target folder id, or {@code null} to move to root
     * @throws ResponseStatusException 400 if the move would create a cycle
     * @throws ResponseStatusException 403 if the node is system-protected
     * @throws ResponseStatusException 404 if either node does not exist
     * @throws ResponseStatusException 422 if the target is not a folder
     */
    @Transactional
    public Document moveToParent(long id, Long targetParentId) {
        DocumentEntity node = findOrThrow(id);

        if (node.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Cannot move a system document");
        }

        // No-op: already in the requested parent
        if (java.util.Objects.equals(node.getParentId(), targetParentId)) {
            return toDto(node);
        }

        // Validate target folder exists and is actually a folder
        if (targetParentId != null) {
            DocumentEntity targetFolder =
                    repo.findById(targetParentId)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Target parent not found"));
            if (!"folder".equals(targetFolder.getType())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Target must be a folder");
            }

            // Cycle check: targetParentId must not be the node itself or any of its descendants
            if (targetParentId.equals(id)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Cannot move a folder into itself");
            }
            List<Long> descendants = repo.findDescendantIds(id);
            if (descendants.contains(targetParentId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot move a folder into one of its own descendants");
            }
        }

        // Append at the end of the new sibling list
        int newPosition = nextSiblingPosition(targetParentId);

        node.setParentId(targetParentId);
        node.setPosition(newPosition);
        node.setUpdatedAt(LocalDateTime.now());

        // Note: descriptionVersion is NOT incremented here — a move does not affect
        // the description, so an existing summary remains valid after the move.

        DocumentEntity saved;
        try {
            saved = repo.save(node);
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document was modified by another request. Please reload and try again.");
        }

        return toDto(saved);
    }

    // ── History ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentHistoryShort> getDescriptionHistory(long docId) {
        if (!repo.existsById(docId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return historyRepo.findDescriptionHistory(docId).stream()
                .map(this::toHistoryShortDto)
                .collect(Collectors.toList());
    }

    /**
     * Returns one specific history snapshot.
     *
     * @param docId the document id
     * @param version the exact version to retrieve
     * @throws ResponseStatusException 404 if the document or the requested version do not exist
     */
    @Transactional(readOnly = true)
    public DocumentHistory getHistoryVersion(long docId, int version) {
        return historyRepo
                .findByDocumentIdAndVersion(docId, version)
                .map(this::toHistoryDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ── Keyword search ────────────────────────────────────────────────────────

    public List<SearchResult> search(String q) {
        return attachParents(keywordHits(q));
    }

    private record RawSearchResult(
            String id, String title, String snippet, LocalDateTime updatedAt, String summary) {}

    /** Keyword hits without breadcrumbs — shared building block for {@link #hybridSearch}. */
    private List<RawSearchResult> keywordHits(String q) {
        return repo.search(q).stream()
                .map(
                        e ->
                                new RawSearchResult(
                                        String.valueOf(e.getId()),
                                        e.getTitle(),
                                        generateSnippet(e.getDescription(), q.toLowerCase()),
                                        e.getUpdatedAt(),
                                        e.getSummary()))
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

        List<RawSearchResult> hits =
                semanticSearchService.search(q, t, l).stream()
                        .map(
                                r ->
                                        new RawSearchResult(
                                                r.id(),
                                                r.title(),
                                                generateSnippet(r.description(), q.toLowerCase()),
                                                r.updatedAt(),
                                                r.summary()))
                        .collect(Collectors.toList());
        return attachParents(hits);
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
        List<RawSearchResult> kwResults = keywordHits(q);
        Map<String, Double> kwScores = new LinkedHashMap<>();
        int kwSize = kwResults.size();
        for (int i = 0; i < kwSize; i++) {
            kwScores.put(kwResults.get(i).id(), (double) (kwSize - i) / kwSize);
        }

        // ── 2. Semantic hits ──────────────────────────────────────────────────
        List<SemanticSearchResult> semResults =
                semanticSearchService.search(q, thr, searchConfig.semantic().limit());
        Map<String, Double> semScores = new LinkedHashMap<>();
        for (SemanticSearchResult r : semResults) {
            semScores.put(r.id(), r.similarity());
        }

        // ── 3. Build unified candidate set ────────────────────────────────────
        Map<String, RawSearchResult> snippets = new HashMap<>();
        for (RawSearchResult sr : kwResults) {
            snippets.put(sr.id(), sr);
        }
        for (SemanticSearchResult sr : semResults) {
            snippets.computeIfAbsent(
                    sr.id(),
                    id ->
                            new RawSearchResult(
                                    id,
                                    sr.title(),
                                    generateSnippet(sr.description(), q.toLowerCase()),
                                    sr.updatedAt(),
                                    sr.summary()));
        }

        // ── 4. Combine scores & sort ──────────────────────────────────────────
        List<RawSearchResult> top =
                snippets.keySet().stream()
                        .map(
                                id -> {
                                    double score =
                                            kw * kwScores.getOrDefault(id, 0.0)
                                                    + sem * semScores.getOrDefault(id, 0.0);
                                    return Map.entry(score, snippets.get(id));
                                })
                        .sorted(Map.Entry.<Double, RawSearchResult>comparingByKey().reversed())
                        .limit(lim)
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toList());

        // Resolve breadcrumbs once, only for the results we actually return.
        return attachParents(top);
    }

    /**
     * Populates {@code parentList} for every result with a single batched ancestor query, returning
     * fresh {@link SearchResult} copies (the record is immutable). Resolving ancestors per result
     * would be an N+1; this is one recursive query for the whole page.
     */
    private List<SearchResult> attachParents(List<RawSearchResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }

        List<Long> ids =
                results.stream().map(r -> Long.parseLong(r.id())).collect(Collectors.toList());
        final Map<Long, List<SearchResult.Parent>> ancestors = repo.findAncestorsByIds(ids);

        return results.stream()
                .map(
                        r ->
                                new SearchResult(
                                        r.id(),
                                        r.title(),
                                        r.snippet(),
                                        r.updatedAt(),
                                        r.summary(),
                                        ancestors.getOrDefault(Long.parseLong(r.id()), List.of())))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DocumentEntity findOrThrow(long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Builds an unsaved history snapshot from the current state of {@code entity}. The snapshot
     * captures the version <em>before</em> the upcoming increment, so if {@code entity.version ==
     * 3}, the history row will record version 3, and after save {@code documents.version} becomes
     * 4.
     */
    private DocumentHistoryEntity snapshotOf(DocumentEntity entity) {
        return new DocumentHistoryEntity(
                null,
                entity.getId(),
                entity.getVersion(),
                entity.getTitle(),
                entity.getType(),
                entity.getDescription(),
                entity.getUpdatedAt(),
                entity.getSummary(),
                entity.getSummarySourceVersion(),
                entity.getDescriptionVersion());
    }

    private Document toDto(DocumentEntity e) {
        return new Document(
                String.valueOf(e.getId()),
                e.getTitle(),
                e.getType(),
                e.getParentId() == null ? null : String.valueOf(e.getParentId()),
                null, // description omitted — fetch via GET /api/documents/{id}
                e.getUpdatedAt(),
                null, // children
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
    }

    private DocumentHistory toHistoryDto(DocumentHistoryEntity e) {
        return new DocumentHistory(
                String.valueOf(e.getDocumentId()),
                e.getVersion(),
                e.getDescriptionVersion(),
                e.getTitle(),
                e.getType(),
                e.getDescription(),
                e.getUpdatedAt(),
                e.getSummary());
    }

    private DocumentHistoryShort toHistoryShortDto(DocumentHistoryShortResult e) {
        return new DocumentHistoryShort(
                String.valueOf(e.documentId()),
                e.version(),
                e.descriptionVersion(),
                e.title(),
                e.type(),
                e.updatedAt());
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
