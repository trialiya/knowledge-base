package io.github.trialiya.kb.service.document;

import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentGrepMatch;
import io.github.trialiya.kb.model.doc.dto.DocumentHistory;
import io.github.trialiya.kb.model.doc.dto.DocumentHistoryShort;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.PagedChildren;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentHistoryEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentHistoryShortResult;
import io.github.trialiya.kb.model.doc.entity.DocumentTreeRow;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentHistoryRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import io.github.trialiya.kb.service.embedding.SemanticSearchService;
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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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

    /** Hard cap on {@link #grepDocuments} blocks, mirroring {@code grepContent}. */
    private static final int MAX_GREP_RESULTS = 200;

    /**
     * Anything that makes a regex mean more than the characters it spells. A pattern without one of
     * these matches exactly the same text as the literal string, which is what lets {@link
     * #grepDocuments} hand it to the database as an {@code ILIKE} prefilter.
     */
    private static final Pattern REGEX_METACHARACTER = Pattern.compile("[\\\\.\\[\\]{}()*+?^$|]");

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

    @Nullable
    public DocumentNode getById(Long id) {
        return repo.findById(id).map(this::toShallowNode).orElse(null);
    }

    /**
     * Returns ancestor IDs from root down to (but not including) the given node. e.g. node at depth
     * 3 → [rootId, folderId, parentFolderId]. Empty list for root-level nodes.
     */
    public List<Long> getAncestorIds(Long id) {
        return repo.findAncestorIds(id);
    }

    /**
     * Returns one page of children for a given parent (null = root), using Spring's {@link
     * Pageable}. Each node includes {@code hasChildren} so the UI can show a chevron without
     * loading the next level eagerly.
     */
    public PagedChildren getChildrenPaged(@Nullable Long parentId, Pageable pageable) {
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
    public List<DocumentNode> getChildren(@Nullable Long parentId) {
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
                                        Objects.requireNonNull(e.getId()),
                                        e.getTitle(),
                                        e.getType().getValue(),
                                        e.getParentId(),
                                        e.getVersion(),
                                        "", // description omitted in skeleton
                                        e.getDescriptionVersion(),
                                        null, // createdAt omitted in skeleton
                                        null, // updatedAt omitted in skeleton
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
                repo.findByParentId(Objects.requireNonNull(e.getId())).stream()
                        .map(
                                c ->
                                        new DocumentNode(
                                                Objects.requireNonNull(c.getId()),
                                                c.getTitle(),
                                                c.getType().getValue(),
                                                c.getParentId(),
                                                c.getVersion(),
                                                "",
                                                c.getDescriptionVersion(),
                                                // description/updatedAt/createdAt all
                                                // deliberately omitted here — this is a stub
                                                // entry (no consumer reads dates off it; the
                                                // paginated children list from toStubNode()
                                                // carries the real metadata), so keep it
                                                // uniformly sparse rather than half-filled.
                                                null,
                                                null,
                                                Collections.emptyList(),
                                                repo.hasChildren(Objects.requireNonNull(c.getId())),
                                                c.isSystem(),
                                                // children in the list carry their own summary
                                                // state so the UI can show badges in the tree
                                                c.getSummary(),
                                                c.isSummaryStale(),
                                                c.getSummarySourceVersion()))
                        .collect(Collectors.toList());
        return new DocumentNode(
                Objects.requireNonNull(e.getId()),
                e.getTitle(),
                e.getType().getValue(),
                e.getParentId(),
                e.getVersion(),
                e.getDescription(),
                e.getDescriptionVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                children,
                !children.isEmpty(),
                e.isSystem(),
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
    }

    private DocumentNode toStubNode(DocumentEntity e) {
        boolean hc = repo.hasChildren(Objects.requireNonNull(e.getId()));
        return new DocumentNode(
                Objects.requireNonNull(e.getId()),
                e.getTitle(),
                e.getType().getValue(),
                e.getParentId(),
                e.getVersion(),
                Objects.requireNonNullElse(snippetOf(e.getDescription()), ""),
                e.getDescriptionVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                Collections.emptyList(),
                hc,
                e.isSystem(),
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
    }

    /** Returns the first {@value #SNIPPET_LENGTH} characters of {@code text}, or null. */
    @Nullable
    private static String snippetOf(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH);
    }

    private DocumentNode buildNode(DocumentEntity e, Map<Long, List<DocumentEntity>> byParent) {
        List<DocumentNode> children =
                byParent.getOrDefault(e.getId(), Collections.emptyList()).stream()
                        .map(child -> buildNode(child, byParent))
                        .collect(Collectors.toList());
        boolean hc = !children.isEmpty() || repo.hasChildren(Objects.requireNonNull(e.getId()));
        return new DocumentNode(
                Objects.requireNonNull(e.getId()),
                e.getTitle(),
                e.getType().getValue(),
                e.getParentId(),
                e.getVersion(),
                "", // description omitted — fetch via GET /api/documents/{id}
                e.getDescriptionVersion(),
                e.getCreatedAt(),
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
        DocumentType type = req.getType() != null ? req.getType() : DocumentType.DOCUMENT;
        int nextPos = nextSiblingPosition(req.getParentId());

        LocalDateTime now = LocalDateTime.now();
        DocumentEntity entity =
                new DocumentEntity(
                        null,
                        req.getTitle(),
                        type,
                        req.getParentId(),
                        Objects.requireNonNullElse(req.getDescription(), ""),
                        now, // createdAt — set once, never updated afterwards
                        now,
                        nextPos,
                        false, // новые узлы никогда не системные
                        0, // version — Spring Data JDBC проставит 1 при INSERT
                        null, // summary — ещё не генерировалось
                        null, // summarySourceVersion
                        1); // descriptionVersion starts at 1
        DocumentEntity saved = repo.save(entity);

        historyRepo.save(snapshotOf(saved));

        tryIndex(Objects.requireNonNull(saved.getId()), saved.getTitle(), saved.getDescription());
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

        tryIndex(Objects.requireNonNull(saved.getId()), saved.getTitle(), saved.getDescription());
        return toDto(saved);
    }

    /**
     * Applies a server-side transformation to the document's description (e.g. replacing a single
     * markdown section) after verifying the caller has seen the current content version.
     *
     * <p>{@code expectedDescriptionVersion} guards the read-modify-write cycle spread across tool
     * calls: the model reads the outline/section (which carries {@code descriptionVersion}),
     * computes a replacement, and passes the version back. If the description changed in between,
     * 409 is returned and the caller must re-read. The patch itself then flows through {@link
     * #update}, so history snapshot, optimistic locking, {@code descriptionVersion} increment,
     * summary staleness and embedding-task enqueueing (transactional outbox) behave exactly as for
     * a full update.
     *
     * @param id document id
     * @param expectedDescriptionVersion the {@code descriptionVersion} the caller read the content
     *     at
     * @param patch pure transformation of the current description (never null — an absent
     *     description is passed as an empty string); runs inside this transaction
     * @throws ResponseStatusException 404 if the document does not exist
     * @throws ResponseStatusException 409 if the content version does not match or on optimistic
     *     lock conflict
     */
    @Transactional
    public Document patchDescription(
            long id, int expectedDescriptionVersion, UnaryOperator<String> patch) {
        return applyPatch(id, expectedDescriptionVersion, patch);
    }

    /**
     * Applies a transformation to the document's description without a version check — for a patch
     * that carries its own evidence of being computed against the current text.
     *
     * <p>The one caller is the exact-match edit ({@code editDocument}): a fragment that still
     * occurs exactly once in the stored text <em>is</em> the concurrency check, and a fragment that
     * no longer occurs fails inside the patch with a message the model can act on. Asking such a
     * caller for a {@code descriptionVersion} too would only add a second way to say the same
     * thing, and a stale one at that. Everything else — history snapshot, optimistic locking,
     * version increment, re-embedding — is exactly as in {@link #patchDescription(long, int,
     * UnaryOperator)}.
     *
     * @throws ResponseStatusException 404 if the document does not exist, 409 on optimistic lock
     *     conflict
     */
    @Transactional
    public Document patchDescription(long id, UnaryOperator<String> patch) {
        return applyPatch(id, null, patch);
    }

    private Document applyPatch(
            long id, @Nullable Integer expectedDescriptionVersion, UnaryOperator<String> patch) {
        DocumentEntity existing = findOrThrow(id);
        if (expectedDescriptionVersion != null
                && existing.getDescriptionVersion() != expectedDescriptionVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document content has changed (current descriptionVersion="
                            + existing.getDescriptionVersion()
                            + ", expected "
                            + expectedDescriptionVersion
                            + "). Re-read the document or its outline and retry.");
        }
        String current = existing.getDescription() == null ? "" : existing.getDescription();

        UpdateDocumentRequest req = new UpdateDocumentRequest();
        req.setDescription(patch.apply(current));
        return update(id, req);
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
                    HttpStatus.UNPROCESSABLE_CONTENT, "Document has no description to summarise");
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

    // ── Move  ────────────────────────────────────────────────────────

    /**
     * Moves a node to {@code targetParentId} (null = root) and places it right after {@code
     * afterId} ({@code null} = first in the level). Single-call replacement for the moveToParent +
     * reorder pair: the client names ONE neighbour instead of sending the whole sibling order, so a
     * lazily-loaded (partially fetched) tree on the frontend can never produce a corrupt order —
     * the slot is resolved here from the current database state.
     *
     * <p>Position strategy (gaps are tolerated everywhere — ordering relies only on relative
     * position, never on contiguity):
     *
     * <ul>
     *   <li><b>Reorder within the same level</b> — windowed shift touching only the rows between
     *       the old and the new slot. Moving up: the window {@code [newPos, oldPos)} gets {@code
     *       +1} and the node takes {@code anchorPos + 1}. Moving down: the window {@code (oldPos,
     *       anchorPos]} gets {@code -1} and the node takes {@code anchorPos} (the anchor itself
     *       shifts one step up, ending right before the node). The moved node is outside the window
     *       by construction, and the slot it vacates is exactly where the window collapses — no
     *       collisions, no need for density.
     *   <li><b>Move to another level</b> — the slot in the target level is opened with one
     *       unbounded {@code position + 1} shift from the insertion point ({@link
     *       DocumentRepository#shiftPositionsFrom}); the hole left in the source level stays.
     * </ul>
     *
     * @param id the document/folder to move
     * @param targetParentId target folder id, or {@code null} for the root level
     * @param afterId sibling to place the node right after, or {@code null} to insert first
     * @throws ResponseStatusException 400 cycle, or {@code afterId == id}
     * @throws ResponseStatusException 403 system node
     * @throws ResponseStatusException 404 node, target parent or afterId not found
     * @throws ResponseStatusException 409 concurrent modification
     * @throws ResponseStatusException 422 target is not a folder, or afterId is in another level
     */
    @Transactional
    public Document move(long id, @Nullable Long targetParentId, @Nullable Long afterId) {
        DocumentEntity node = findOrThrow(id);

        if (node.isSystem()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Cannot move a system document");
        }
        if (afterId != null && afterId == id) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "afterId must not be the node itself");
        }

        validateTargetParent(id, targetParentId);

        // Resolve the anchor from CURRENT database state — the client only names a neighbour,
        // so its (possibly partial) view of the level can't corrupt positions.
        // anchorPos: position of `afterId`, or the level's MIN position for "insert first".
        final int anchorPos;
        if (afterId == null) {
            anchorPos = repo.findMinPosition(targetParentId);
        } else {
            DocumentEntity after =
                    repo.findById(afterId)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND, "afterId not found"));
            if (!Objects.equals(after.getParentId(), targetParentId)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "afterId is not a child of the target parent");
            }
            anchorPos = after.getPosition();
        }

        boolean sameParent = Objects.equals(node.getParentId(), targetParentId);
        int oldPosition = node.getPosition();
        int newPosition;

        if (!sameParent) {
            // ── Cross-level move: open the slot with an unbounded shift ──────────
            // "after anchor" → slot anchorPos + 1; "insert first" → the level's MIN.
            newPosition = afterId == null ? anchorPos : anchorPos + 1;
            repo.shiftPositionsFrom(targetParentId, newPosition, id);
        } else if (anchorPos < oldPosition) {
            // ── Reorder up ───────────────────────────────────────────────────────
            // Slot right after the anchor (or the MIN slot itself for "insert first").
            newPosition = afterId == null ? anchorPos : anchorPos + 1;
            if (newPosition == oldPosition) {
                return toDto(node); // already exactly there — no-op
            }
            // [newPosition, oldPosition) steps down by one; the +1 window collapses
            // into the slot the node vacates at oldPosition.
            repo.shiftWindowUp(targetParentId, newPosition, oldPosition);
        } else if (anchorPos > oldPosition) {
            // ── Reorder down ─────────────────────────────────────────────────────
            // (oldPosition, anchorPos] steps up by one — the anchor itself moves to
            // anchorPos - 1, ending right BEFORE the node, which takes anchorPos.
            newPosition = anchorPos;
            repo.shiftWindowDown(targetParentId, oldPosition, anchorPos);
        } else {
            // anchorPos == oldPosition: "insert first" while already first.
            return toDto(node); // no-op
        }

        node.setParentId(targetParentId);
        node.setPosition(newPosition);
        node.setUpdatedAt(LocalDateTime.now());

        // descriptionVersion is NOT incremented — a move does not affect the description,
        // so an existing summary remains valid (same contract as moveToParent).

        try {
            return toDto(repo.save(node));
        } catch (OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Document was modified by another request. Please reload and try again.");
        }
    }

    /**
     * Target-parent validation shared by {@link #move}: the target must exist, be a folder, and
     * must not be the node itself or any of its descendants (cycle check). {@code null} target
     * (root level) is always valid.
     */
    private void validateTargetParent(long id, @Nullable Long targetParentId) {
        if (targetParentId == null) return;

        DocumentEntity targetFolder =
                repo.findById(targetParentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Target parent not found"));
        if (targetFolder.getType() != DocumentType.FOLDER) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Target must be a folder");
        }

        // Cycle check: targetParentId must not be the node itself or any of its descendants
        if (targetParentId.equals(id)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot move a folder into itself");
        }
        List<Long> descendants = repo.findDescendantIds(id);
        if (descendants.contains(targetParentId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot move a folder into one of its own descendants");
        }
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

    // ── Content grep ──────────────────────────────────────────────────────────

    /**
     * Grep over the markdown bodies of the knowledge base: every line matching {@code pattern},
     * with its context, addressed by document and section.
     *
     * <p>The complement of {@link #search}/{@link #hybridSearch}, which rank whole documents by
     * relevance: this one answers "where exactly does this string occur", the question an edit
     * starts from. Matching runs in Java rather than in SQL because {@code REGEXP} is spelled
     * differently in PostgreSQL and H2, and because the section path of a hit needs the markdown
     * parsed anyway.
     *
     * <p>Bodies are read one at a time ({@code findDescriptionById}) over a structural row list,
     * the same shape export and sync use — a knowledge base can be far larger than the heap, and a
     * grep must not be the operation that finds out. That costs a query per candidate, so the
     * candidate list is narrowed in SQL first whenever the pattern is a plain string ({@code
     * ILIKE}) — which covers the common call, {@code regex=true} included, since most patterns
     * carry no metacharacter at all. A pattern that really is a regex has no such prefilter and
     * scans every body.
     *
     * @param pattern literal fragment, or a regex when {@code regex} is true; always
     *     case-insensitive
     * @param regex treat {@code pattern} as a regular expression
     * @param contextLines lines kept around each match (clamped to 0..{@value
     *     DocumentGrep#MAX_CONTEXT_LINES})
     * @param maxResults cap on returned blocks (clamped to 1..{@value #MAX_GREP_RESULTS})
     * @param documentId restrict the search to this document and its descendants; {@code null}
     *     searches the whole base
     * @return match blocks ordered by document, then by position in it; empty when nothing matched
     */
    public List<DocumentGrepMatch> grepDocuments(
            String pattern,
            boolean regex,
            int contextLines,
            int maxResults,
            @Nullable Long documentId) {
        int ctx = Math.clamp(contextLines, 0, DocumentGrep.MAX_CONTEXT_LINES);
        int limit = Math.clamp(maxResults, 1, MAX_GREP_RESULTS);
        Pattern compiled = DocumentGrep.compile(pattern, regex);

        String literal = literalOf(pattern, regex);
        List<DocumentTreeRow> rows =
                literal == null
                        ? repo.findRowsWithDescription()
                        : repo.findRowsWithDescriptionContaining(literal);
        if (documentId != null) {
            Set<Long> subtree = Set.copyOf(repo.findDescendantIds(documentId));
            if (subtree.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Document id=" + documentId + " not found");
            }
            rows = rows.stream().filter(row -> subtree.contains(row.id())).toList();
        }

        List<DocumentGrepMatch> matches = new ArrayList<>();
        for (DocumentTreeRow row : rows) {
            if (matches.size() >= limit) {
                break;
            }
            String description = repo.findDescriptionById(row.id()).orElse("");
            matches.addAll(
                    DocumentGrep.matches(
                            row.id(),
                            row.title(),
                            description,
                            compiled,
                            ctx,
                            limit - matches.size()));
        }
        log.info(
                "grepDocuments: pattern='{}' regex={} ctx={} documentId={} — {} block(s) over {}"
                        + " candidate document(s)",
                pattern,
                regex,
                ctx,
                documentId,
                matches.size(),
                rows.size());
        return List.copyOf(matches);
    }

    /**
     * The pattern as a plain substring the database can filter on, or {@code null} when it has to
     * be matched in Java. A {@code regex=false} pattern always qualifies; a regex qualifies when it
     * contains no metacharacter, which most of them do not — the argument defaults to true and
     * models pass ordinary words through it.
     */
    private static @Nullable String literalOf(String pattern, boolean regex) {
        if (!regex) {
            return pattern;
        }
        return REGEX_METACHARACTER.matcher(pattern).find() ? null : pattern;
    }

    // ── Keyword search ────────────────────────────────────────────────────────

    public List<SearchResult> search(String q) {
        return attachParents(keywordHits(q));
    }

    private record RawSearchResult(
            long id,
            String title,
            String snippet,
            LocalDateTime updatedAt,
            @Nullable String summary) {}

    /** Keyword hits without breadcrumbs — shared building block for {@link #hybridSearch}. */
    private List<RawSearchResult> keywordHits(String q) {
        return repo.search(q).stream()
                .map(
                        e ->
                                new RawSearchResult(
                                        Objects.requireNonNull(e.getId()),
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
    public List<SearchResult> semanticSearch(
            String q, @Nullable Double threshold, @Nullable Integer limit) {
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
            String q,
            @Nullable Double threshold,
            @Nullable Integer limit,
            @Nullable Double kwWeight,
            @Nullable Double semWeight) {

        SearchConfiguration.HybridConfig cfg = searchConfig.hybrid();
        double kw = kwWeight != null ? kwWeight : cfg.keywordWeight();
        double sem = semWeight != null ? semWeight : cfg.semanticWeight();
        double thr = threshold != null ? threshold : cfg.threshold();
        int lim = limit != null ? limit : cfg.limit();

        // ── 1. Keyword hits ───────────────────────────────────────────────────
        List<RawSearchResult> kwResults = keywordHits(q);
        Map<Long, Double> kwScores = new LinkedHashMap<>();
        int kwSize = kwResults.size();
        for (int i = 0; i < kwSize; i++) {
            kwScores.put(kwResults.get(i).id(), (double) (kwSize - i) / kwSize);
        }

        // ── 2. Semantic hits ──────────────────────────────────────────────────
        List<SemanticSearchResult> semResults =
                semanticSearchService.search(q, thr, searchConfig.semantic().limit());
        Map<Long, Double> semScores = new LinkedHashMap<>();
        for (SemanticSearchResult r : semResults) {
            semScores.put(r.id(), r.similarity());
        }

        // ── 3. Build unified candidate set ────────────────────────────────────
        Map<Long, RawSearchResult> snippets = new HashMap<>();
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

        List<Long> ids = results.stream().map(RawSearchResult::id).collect(Collectors.toList());
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
                                        ancestors.getOrDefault(r.id(), List.of())))
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
                Objects.requireNonNull(entity.getId()),
                entity.getVersion(),
                entity.getTitle(),
                entity.getType().getValue(),
                entity.getDescription(),
                entity.getUpdatedAt(),
                entity.getSummary(),
                entity.getSummarySourceVersion(),
                entity.getDescriptionVersion());
    }

    private Document toDto(DocumentEntity e) {
        return new Document(
                Objects.requireNonNull(e.getId()),
                e.getTitle(),
                e.getType().getValue(),
                e.getParentId(),
                e.getVersion(),
                e.getDescriptionVersion(),
                null, // description omitted — fetch via GET /api/documents/{id}
                e.getCreatedAt(),
                e.getUpdatedAt(),
                null, // children
                e.getSummary(),
                e.isSummaryStale(),
                e.getSummarySourceVersion());
    }

    private DocumentHistory toHistoryDto(DocumentHistoryEntity e) {
        return new DocumentHistory(
                e.getDocumentId(),
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
                e.documentId(),
                e.version(),
                e.descriptionVersion(),
                e.title(),
                e.type(),
                e.updatedAt());
    }

    private String generateSnippet(@Nullable String content, String query) {
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

    /**
     * Next free slot at the end of a level. Asks the database for the maximum directly instead of
     * loading the level and folding over it in Java: creating N siblings in a row (bulk import)
     * otherwise costs N level-wide selects, each carrying every sibling's body.
     */
    private int nextSiblingPosition(@Nullable Long parentId) {
        return repo.findMaxPosition(parentId) + 1;
    }
}
