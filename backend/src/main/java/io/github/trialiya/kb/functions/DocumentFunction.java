package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.orDefault;
import static io.github.trialiya.kb.tools.ToolArgs.positiveOrDefault;
import static io.github.trialiya.kb.tools.ToolArgs.requireContent;
import static io.github.trialiya.kb.tools.ToolArgs.requireId;
import static io.github.trialiya.kb.tools.ToolArgs.requireInt;
import static io.github.trialiya.kb.tools.ToolArgs.requireNonEmpty;
import static io.github.trialiya.kb.tools.ToolArgs.requireText;
import static io.github.trialiya.kb.tools.ToolArgs.requireValue;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.DocumentGrepMatch;
import io.github.trialiya.kb.model.doc.dto.DocumentNameMatch;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.DocumentOutline;
import io.github.trialiya.kb.model.doc.dto.DocumentSection;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.doc.dto.DocumentSkeletonNode;
import io.github.trialiya.kb.model.doc.dto.DocumentView;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.SectionRename;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.document.DocumentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.EarlierToolResults;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.utils.ExactEdit;
import io.github.trialiya.kb.utils.MarkdownSections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that give the chat model read/write access to the knowledge-base.
 *
 * <p>Capabilities:
 *
 * <ul>
 *   <li>{@link #searchDocuments} — hybrid search (keyword + semantic).
 *   <li>{@link #grepDocuments} — line-level search over document bodies (grep over the base).
 *   <li>{@link #findDocumentsByName} — lookup by title (exact or partial match).
 *   <li>{@link #getTreeSkeleton} — lightweight flat list of all nodes (id/title/type only).
 *   <li>{@link #getDocument} — full content of a single node by id.
 *   <li>{@link #getDocumentOutline} — markdown section outline of a document (no content).
 *   <li>{@link #getDocumentSection} — content of a single markdown section.
 *   <li>{@link #updateDocumentSection} — replace a single markdown section.
 *   <li>{@link #insertDocumentSection} — insert a new section before/after an existing one.
 *   <li>{@link #deleteDocumentSection} — delete a section subtree.
 *   <li>{@link #renameDocumentSections} — bulk-rename section headings.
 *   <li>{@link #createDocument} — create a new document or folder.
 *   <li>{@link #updateDocument} — edit title and/or content of an existing document.
 *   <li>{@link #editDocument} — exact-match fragment replacement inside a document.
 *   <li>{@link #retryDocumentWrite} — repeat a write refused by the read-before-write guard.
 *   <li>{@link #deleteDocument} — delete a document (and its descendants).
 *   <li>{@link #copyAttachmentToDocument} — copy an attachment from the current chat to a document.
 * </ul>
 */
@Slf4j
public class DocumentFunction {

    /**
     * Узлов в ответе {@code getTreeSkeleton}. Столько же «Обзор» чата берётся показать деревом
     * ({@code treeResult.js}): больше — не дерево, а выгрузка.
     */
    static final int MAX_SKELETON_NODES = 1000;

    private final DocumentService documentService;
    private final AttachmentService attachmentService;
    private final DocumentReadGuard readGuard;

    /**
     * @param earlier the tool results of the chat's live window — what lets a document read in an
     *     earlier turn count for a write in this one (see {@link DocumentReadGuard})
     */
    public DocumentFunction(
            DocumentService documentService, AttachmentService attachmentService, EarlierToolResults earlier) {
        this.documentService = documentService;
        this.attachmentService = attachmentService;
        this.readGuard = new DocumentReadGuard(documentService, earlier);
    }

    /** Where {@link #insertDocumentSection} places the new section relative to its anchor. */
    public enum InsertPosition {
        BEFORE,
        AFTER;

        /**
         * Accepts the casing the model actually sent. The schema advertises the constants in upper
         * case, but weak models answer "before" or " After" often enough that the strict Jackson
         * default — a deserialization failure whose message names neither the argument nor the
         * accepted values — is a worse answer than simply understanding them.
         */
        @JsonCreator
        static InsertPosition parse(String raw) {
            final String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "BEFORE" -> BEFORE;
                case "AFTER" -> AFTER;
                default ->
                    throw new IllegalArgumentException(
                            "Tool argument 'position' must be BEFORE or AFTER, got \"" + raw + "\".");
            };
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Hybrid search across the knowledge base (keyword + semantic).
     *
     * @param query natural-language or keyword search string
     * @param mode search mode: "hybrid" (default), "semantic", or "keyword"
     * @param threshold minimum cosine similarity for semantic/hybrid (0..1)
     * @param limit maximum number of results
     * @param kwWeight keyword score weight for hybrid mode (0..1)
     * @param semWeight semantic score weight for hybrid mode (0..1)
     * @return list of matching documents with title, snippet, and update time
     */
    @Tool(
            description = "Search knowledge base documents by topic/keywords (hybrid: keyword + semantic).",
            resultConverter = CompactToolResultConverter.class)
    public List<SearchResult> searchDocuments(
            @ToolParam(description = "Search query in any language.") String query,
            @ToolParam(description = "Search mode: hybrid (default), semantic, keyword.", required = false) @Nullable
                    String mode,
            @ToolParam(
                            description = "Minimum cosine similarity for semantic/hybrid search (0.0–1.0).",
                            required = false)
                    @Nullable
                    Double threshold,
            @ToolParam(description = "Maximum number of results.", required = false) @Nullable Integer limit,
            @ToolParam(description = "Keyword weight in hybrid mode (0.0–1.0).", required = false) @Nullable
                    Double kwWeight,
            @ToolParam(description = "Semantic weight in hybrid mode (0.0–1.0).", required = false) @Nullable
                    Double semWeight) {
        // No empty-query fallback: semantic and hybrid both embed the query, and the embedding API
        // rejects an empty string — the "safe" default would fail deeper down with a worse message.
        requireText(query, "query");
        final String effectiveMode = orDefault(mode, "hybrid").toLowerCase(Locale.ROOT);
        log.debug("Document search: query='{}' mode={} threshold={} limit={}", query, effectiveMode, threshold, limit);

        return switch (effectiveMode) {
            case "semantic" -> documentService.semanticSearch(query, threshold, limit);
            case "keyword" -> documentService.search(query);
            default -> documentService.hybridSearch(query, threshold, limit, kwWeight, semWeight);
        };
    }

    /**
     * Grep over the markdown bodies of the knowledge base: the lines that match, with their context
     * and the section they sit in — the documentary twin of {@code grepContent}.
     *
     * <p>Answers a different question from {@link #searchDocuments}: that one ranks whole documents
     * by relevance, this one says exactly where a string occurs, which is where an edit starts.
     *
     * @param pattern literal fragment, or a regex when {@code regex=true}; always case-insensitive
     * @param regex treat the pattern as a regular expression (default true, as in {@code
     *     grepContent})
     * @param contextLines lines of context around each match (0–10, default 1)
     * @param maxResults maximum number of match blocks (1–200, default 50)
     * @param documentId restrict the search to this document and its descendants
     * @return match blocks with documentId, title, sectionPath, line number and text
     */
    @Tool(
            description =
                    "Search document CONTENT for matching lines (case-insensitive), like grep over the knowledge base. "
                            + "Returns documentId, title, sectionPath, line number and text; a line over 500 characters is cut. "
                            + "Use it to find where a wording occurs before editing; use searchDocuments to find which document is about a topic.",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentGrepMatch> grepDocuments(
            @ToolParam(description = "Search pattern: literal string or regex (if regex=true).") String pattern,
            @ToolParam(
                            description = "Treat pattern as regex (true, default) or literal substring (false).",
                            required = false)
                    @Nullable
                    Boolean regex,
            @ToolParam(description = "Context lines before/after match (0–10, default 1).", required = false) @Nullable
                    Integer contextLines,
            @ToolParam(description = "Maximum match blocks to return (1–200, default 50).", required = false) @Nullable
                    Integer maxResults,
            @ToolParam(
                            description = "Optional: search only inside this document and its descendants. "
                                    + "Omit to search the whole knowledge base.",
                            required = false)
                    @Nullable
                    Long documentId) {
        requireText(pattern, "pattern");
        final boolean useRegex = orDefault(regex, true);
        // As in grepContent: 0 context lines is a real answer ("the matching line only"), so this
        // defaults through orDefault rather than positiveOrDefault. The service clamps the range.
        final int ctx = orDefault(contextLines, 1);
        final int limit = positiveOrDefault(maxResults, 50);
        log.debug(
                "grepDocuments called: pattern='{}' regex={} contextLines={} maxResults={} documentId={}",
                pattern,
                useRegex,
                ctx,
                limit,
                documentId);
        return documentService.grepDocuments(pattern, useRegex, ctx, limit, documentId).stream()
                .map(m -> new DocumentGrepMatch(
                        m.documentId(), m.title(), m.sectionPath(), m.matchLine(), GrepLines.cap(m.text())))
                .toList();
    }

    // ── Tree ──────────────────────────────────────────────────────────────────

    /**
     * Returns a flat list of ALL nodes (id, title, type, parentId, hasChildren) without
     * descriptions or content. Use this to understand the knowledge-base structure or to enumerate
     * available documents. For content, call {@link #getDocument}.
     *
     * @return flat list of skeleton nodes; parentId=null means root level
     */
    @Tool(
            description = "List all knowledge base nodes (id, title, type, parentId) without content. "
                    + "Over "
                    + MAX_SKELETON_NODES
                    + " nodes, only the upper levels that fit are listed: a node with hasChildren=true "
                    + "whose children are not in the list is a folder to open with getDocument.",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentSkeletonNode> getTreeSkeleton() {
        log.debug("getTreeSkeleton called");
        return upperLevels(documentService.getTreeSkeleton(), MAX_SKELETON_NODES);
    }

    /**
     * Узлы верхних уровней дерева — столько уровней целиком, сколько влезает в {@code max}. Резать
     * по уровням, а не по счёту: обрезанный список остаётся деревом, и каждый узел, чьих детей в нём
     * нет, сам говорит об этом через {@code hasChildren}. Если не влезают даже корни — первые
     * {@code max} из них. Порядок исходного списка сохраняется.
     */
    static List<DocumentSkeletonNode> upperLevels(List<DocumentSkeletonNode> nodes, int max) {
        if (nodes.size() <= max) {
            return nodes;
        }
        Map<Long, Long> parentOf = new HashMap<>();
        nodes.forEach(n -> parentOf.put(n.id(), n.parentId()));
        Map<Long, Integer> depth = new HashMap<>();
        for (DocumentSkeletonNode node : nodes) {
            depth.put(node.id(), depthOf(node.id(), parentOf, nodes.size()));
        }
        int[] perLevel = new int[nodes.size() + 1];
        depth.values().forEach(d -> perLevel[d]++);
        int level = -1;
        int taken = 0;
        while (level + 1 < perLevel.length && taken + perLevel[level + 1] <= max) {
            level++;
            taken += perLevel[level];
        }
        if (level < 0) {
            return nodes.stream()
                    .filter(n -> depth.getOrDefault(n.id(), 0) == 0)
                    .limit(max)
                    .toList();
        }
        int deepest = level;
        return nodes.stream()
                .filter(n -> depth.getOrDefault(n.id(), 0) <= deepest)
                .toList();
    }

    /** Глубина узла: 0 у корня и у узла, чей родитель вне списка; цепочка длиннее списка — цикл. */
    private static int depthOf(long id, Map<Long, Long> parentOf, int bound) {
        int depth = 0;
        Long parent = parentOf.get(id);
        while (parent != null && parentOf.containsKey(parent) && depth < bound) {
            depth++;
            parent = parentOf.get(parent);
        }
        return depth;
    }

    // ── Find by name ──────────────────────────────────────────────────────────

    /**
     * Finds documents or folders by title (exact or partial match, case-insensitive).
     *
     * <p>Use this when the user refers to a document by name and you need its id or content.
     * Exact-title matches are returned first; partial matches follow ordered by title length.
     * Returns up to 20 results.
     *
     * <p>Unlike {@link #searchDocuments}, this tool matches <em>only the title</em> — it will not
     * surface documents that merely mention the name in their body text.
     *
     * @param name full or partial document/folder title
     * @return matching documents with id, title, type, parentId and the start of the description
     */
    @Tool(
            description = "Find document/folder by title (exact or partial match, case-insensitive, "
                    + "exact matches first). Matches ONLY the title, not content.",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentNameMatch> findDocumentsByName(
            @ToolParam(description = "Document/folder title (full or partial).") String name) {
        // "" matches every title; the model that wants the whole list has getTreeSkeleton for it.
        requireText(name, "name");
        log.debug("findDocumentsByName called: name='{}'", name);
        return documentService.findByName(name).stream()
                .map(DocumentNameMatch::of)
                .toList();
    }

    // ── Single document ───────────────────────────────────────────────────────

    /**
     * Fetches a single document or folder by id, including its full description/content and a list
     * of its direct children (shallow, without their descriptions).
     *
     * @param documentId document or folder id (from {@link #getTreeSkeleton} results)
     * @return document node with description, updatedAt, and direct children as skeleton nodes
     */
    @Tool(
            description = "Read full document/folder content by id, including direct children (shallow).",
            resultConverter = CompactToolResultConverter.class)
    public DocumentView getDocument(@ToolParam(description = "Document or folder id.") Long documentId) {
        final long id = requireId(documentId, "documentId");
        log.debug("getDocument called: documentId={}", id);
        return DocumentView.of(requireDocument(id));
    }

    // ── Markdown sections ─────────────────────────────────────────────────────

    /**
     * Returns the markdown outline of a document: section paths, levels, titles and sizes without
     * any content. Cheap navigation entry point for large documents — the model picks a section and
     * fetches/updates only it via {@link #getDocumentSection} / {@link #updateDocumentSection}.
     *
     * @param documentId document id
     * @return outline with the current descriptionVersion and a flat, document-ordered section list
     */
    @Tool(
            description = "Get markdown outline (section titles, levels, sizes) without content.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentOutline getDocumentOutline(@ToolParam(description = "Document id.") Long documentId) {
        final long id = requireId(documentId, "documentId");
        log.debug("getDocumentOutline called: documentId={}", id);
        DocumentNode node = requireDocument(id);
        List<MarkdownSections.Section> sections = MarkdownSections.parse(descriptionOf(node));
        return new DocumentOutline(
                node.id(),
                node.title(),
                node.descriptionVersion(),
                sections.stream()
                        .map(s -> new DocumentOutline.OutlineSection(
                                s.path(), s.level(), s.title(), s.chars(), s.subsections()))
                        .toList());
    }

    /**
     * Fetches a single markdown section (heading + body + subsections) of a document.
     *
     * @param documentId document id
     * @param sectionPath section address from {@link #getDocumentOutline}
     * @return section content with the current descriptionVersion
     */
    @Tool(
            description = "Read one markdown section (heading + body + subsections) without full load.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentSection getDocumentSection(
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "Section path from getDocumentOutline (e.g., \"Setup > Docker\").")
                    String sectionPath) {
        final long id = requireId(documentId, "documentId");
        requireText(sectionPath, "sectionPath");
        log.debug("getDocumentSection called: documentId={} sectionPath='{}'", id, sectionPath);
        DocumentNode node = requireDocument(id);
        String description = descriptionOf(node);
        MarkdownSections.Section section = findSectionOrThrow(description, sectionPath);
        return new DocumentSection(
                node.id(),
                section.path(),
                node.descriptionVersion(),
                description.substring(section.startOffset(), section.endOffset()));
    }

    /**
     * Replaces a single markdown section (the whole subtree: heading + body + subsections) without
     * transferring the rest of the document. The splice happens server-side inside one transaction.
     *
     * <p>Two safety checks:
     *
     * <ul>
     *   <li>Read-before-write guard (same idea as {@link #updateDocument}): the section must have
     *       been read via {@link #getDocumentSection} (same path) or {@link #getDocument} — see
     *       {@link DocumentReadGuard}.
     *   <li>{@code expectedDescriptionVersion} (from outline/section) is compared with the current
     *       one inside the transaction — a concurrent edit yields a conflict error instead of
     *       splicing against stale section boundaries.
     * </ul>
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param sectionPath section address from {@link #getDocumentOutline}
     * @param newContent full replacement text of the section, starting with its heading
     * @param expectedDescriptionVersion descriptionVersion the section/outline was read at
     * @return updated document
     */
    @Tool(
            description =
                    "Replace one markdown section. Read section (getDocumentSection) or document (getDocument) first. One operation per call; re-read outline afterward.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocumentSection(
            ToolContext context,
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "Section path from getDocumentOutline; _preamble = text before first heading.")
                    String sectionPath,
            @ToolParam(
                            description = "Full new section text, starting with its heading (e.g., \"## Title\"). "
                                    + "Link other documents as [Title](/?doc=ID).")
                    String newContent,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocumentSection.")
                    Integer expectedDescriptionVersion) {
        final long id = requireId(documentId, "documentId");
        requireText(sectionPath, "sectionPath");
        requireContent(newContent, "newContent");
        final int version = requireInt(expectedDescriptionVersion, "expectedDescriptionVersion");

        log.debug("updateDocumentSection called: id={} sectionPath='{}' expectedDescVer={}", id, sectionPath, version);

        readGuard.requireSectionRead(context, id, sectionPath);
        if (newContent.isBlank()) {
            throw new IllegalArgumentException(
                    "newContent пуст. Передай полный новый текст секции, начиная с её заголовка.");
        }
        if (!MarkdownSections.PREAMBLE_PATH.equals(sectionPath)) {
            requireStartsWithHeading(newContent);
        }

        return documentService
                .patchDescription(
                        id,
                        version,
                        current -> MarkdownSections.replaceSection(
                                current, findSectionOrThrow(current, sectionPath), newContent))
                .toDocumentShort();
    }

    /**
     * Inserts a new markdown section before or after an existing section subtree. Requires the
     * document structure to have been read ({@link #getDocumentOutline}, {@link #getDocument} or
     * {@link #getDocumentSection} of the anchor — see {@link DocumentReadGuard}) and the version
     * check of {@link DocumentService#patchDescription}.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param anchorSectionPath existing section the new one is placed next to
     * @param position "before" or "after" the anchor subtree
     * @param newContent full text of the new section, starting with its heading
     * @param expectedDescriptionVersion descriptionVersion the outline/document was read at
     * @return updated document
     */
    @Tool(
            description =
                    "Insert new section before/after existing one. Read outline (getDocumentOutline) or document (getDocument) first. One operation per call; re-read outline after (paths/versions change).",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort insertDocumentSection(
            ToolContext context,
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "Existing anchor section path from getDocumentOutline.") String anchorSectionPath,
            @ToolParam(description = "Position: BEFORE or AFTER the anchor.") InsertPosition position,
            @ToolParam(
                            description = "Full text of new section, starting with its heading (e.g., \"## Title\"). "
                                    + "Link other documents as [Title](/?doc=ID).")
                    String newContent,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocument.")
                    Integer expectedDescriptionVersion) {
        final long id = requireId(documentId, "documentId");
        requireText(anchorSectionPath, "anchorSectionPath");
        requireValue(position, "position");
        requireText(newContent, "newContent");
        final int version = requireInt(expectedDescriptionVersion, "expectedDescriptionVersion");

        log.debug(
                "insertDocumentSection called: id={} anchor='{}' position={} expectedDescVer={}",
                id,
                anchorSectionPath,
                position,
                version);

        readGuard.requireStructureRead(context, id, anchorSectionPath);
        boolean before = position == InsertPosition.BEFORE;
        if (before && MarkdownSections.PREAMBLE_PATH.equals(anchorSectionPath)) {
            throw new IllegalArgumentException("Вставка before _preamble невозможна — используй after.");
        }
        requireStartsWithHeading(newContent);

        return documentService
                .patchDescription(
                        id,
                        version,
                        current -> MarkdownSections.insertSection(
                                current, findSectionOrThrow(current, anchorSectionPath), newContent, before))
                .toDocumentShort();
    }

    /**
     * Deletes a markdown section subtree (heading + body + subsections). The section must have been
     * read via {@link #getDocumentSection} (same path) or {@link #getDocument} in the same
     * chat-response session, so the model never deletes content it has not seen.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param sectionPath section address from {@link #getDocumentOutline}
     * @param expectedDescriptionVersion descriptionVersion the section/outline was read at
     * @return updated document
     */
    @Tool(
            description =
                    "Delete one markdown section. Read section (getDocumentSection) or document (getDocument) first. One operation per call; re-read outline after.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort deleteDocumentSection(
            ToolContext context,
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "Section path from getDocumentOutline; _preamble = text before first heading.")
                    String sectionPath,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocumentSection.")
                    Integer expectedDescriptionVersion) {
        final long id = requireId(documentId, "documentId");
        requireText(sectionPath, "sectionPath");
        final int version = requireInt(expectedDescriptionVersion, "expectedDescriptionVersion");

        log.debug("deleteDocumentSection called: id={} sectionPath='{}' expectedDescVer={}", id, sectionPath, version);

        readGuard.requireSectionRead(context, id, sectionPath);

        return documentService
                .patchDescription(
                        id,
                        version,
                        current ->
                                MarkdownSections.replaceSection(current, findSectionOrThrow(current, sectionPath), ""))
                .toDocumentShort();
    }

    /**
     * Renames several section headings in one atomic operation (levels and bodies untouched).
     * Useful right after {@link #insertDocumentSection}/{@link #deleteDocumentSection} to fix
     * numbering. Section paths are resolved against the same document state, so renaming a parent
     * and its children in one call works with the paths of the current outline.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param renames section path → new heading title pairs; paths must be distinct
     * @param expectedDescriptionVersion descriptionVersion the outline/document was read at
     * @return updated document
     */
    @Tool(
            description =
                    "Bulk-rename section headings (atomic operation). Example: fix numbering after insert/delete. Read outline first. One operation per call; re-read afterward.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort renameDocumentSections(
            ToolContext context,
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "List of renames: {sectionPath, newTitle}.") List<SectionRename> renames,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocument.")
                    Integer expectedDescriptionVersion) {
        final long id = requireId(documentId, "documentId");
        final int version = requireInt(expectedDescriptionVersion, "expectedDescriptionVersion");

        log.debug(
                "renameDocumentSections called: id={} renames={} expectedDescVer={}",
                id,
                renames == null ? null : renames.size(),
                version);

        readGuard.requireStructureRead(context, id, null);
        requireNonEmpty(renames, "renames");
        if (renames.stream().map(SectionRename::sectionPath).distinct().count() != renames.size()) {
            throw new IllegalArgumentException("Пути секций в renames должны быть уникальными.");
        }
        for (SectionRename rename : renames) {
            if (MarkdownSections.PREAMBLE_PATH.equals(rename.sectionPath())) {
                throw new IllegalArgumentException("_preamble не имеет заголовка.");
            }
            String title = rename.newTitle() == null ? "" : rename.newTitle().strip();
            if (title.isBlank() || title.contains("\n") || title.startsWith("#")) {
                throw new IllegalArgumentException("newTitle для '"
                        + rename.sectionPath()
                        + "' должен быть непустой одной строкой без ведущих #.");
            }
        }

        return documentService
                .patchDescription(id, version, current -> {
                    // Resolve every path against the same text, then splice from the
                    // bottom of the document up so a rename never shifts the offsets of
                    // the sections still to be renamed.
                    record Resolved(MarkdownSections.Section section, String newTitle) {}
                    String result = current;
                    for (Resolved r : renames.stream()
                            .map(rn -> new Resolved(
                                    findSectionOrThrow(current, rn.sectionPath()),
                                    rn.newTitle().strip()))
                            .sorted(Comparator.comparingInt(
                                            (Resolved r) -> r.section().startOffset())
                                    .reversed())
                            .toList()) {
                        result = MarkdownSections.renameHeading(result, r.section(), r.newTitle());
                    }
                    return result;
                })
                .toDocumentShort();
    }

    /** Loads a node by id or fails with a model-readable error (getById returns null quietly). */
    private DocumentNode requireDocument(long documentId) {
        DocumentNode node = documentService.getById(documentId);
        if (node == null) {
            throw new IllegalArgumentException("Документ id=" + documentId + " не найден.");
        }
        return node;
    }

    private static String descriptionOf(DocumentNode node) {
        return node.description() == null ? "" : node.description();
    }

    private static MarkdownSections.Section findSectionOrThrow(String markdown, String sectionPath) {
        List<MarkdownSections.Section> sections = MarkdownSections.parse(markdown);
        return sections.stream()
                .filter(s -> s.path().equals(sectionPath))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Секция '"
                        + sectionPath
                        + "' не найдена. Доступные секции: "
                        + sections.stream()
                                .map(MarkdownSections.Section::path)
                                .limit(50)
                                .collect(Collectors.joining(", "))
                        + ". Вызови getDocumentOutline для актуального "
                        + "оглавления."));
    }

    /**
     * Creates a new document or folder in the knowledge base.
     *
     * @param title document title
     * @param type "document" or "folder"
     * @param parentId parent folder id (null for root level)
     * @param description document content / body text
     * @return created document with its new id
     */
    @Tool(
            description = "Create new document or folder in the knowledge base.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort createDocument(
            @ToolParam(description = "Document or folder title.") String title,
            @ToolParam(description = "Type: 'document' or 'folder'.", required = false) @Nullable String type,
            @ToolParam(description = "Parent folder id (null or empty for root level).", required = false) @Nullable
                    Long parentId,
            @ToolParam(
                            description = "Document content (text or markdown). Link other knowledge "
                                    + "base documents as [Title](/?doc=ID).",
                            required = false)
                    @Nullable
                    String description) {
        requireText(title, "title");

        log.debug("createDocument called: title='{}' type={} parentId={}", title, type, parentId);

        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(title);
        req.setType(type != null && !type.isBlank() ? DocumentType.fromValue(type) : DocumentType.DOCUMENT);
        req.setParentId(parentId);
        req.setDescription(description);

        return documentService.create(req).toDocumentShort();
    }

    /**
     * Updates an existing document's title and/or content.
     *
     * <p>Guard: a content update ({@code description != null}) is rejected unless the model has
     * seen this document through {@link #getDocument} — in this response, or earlier in the chat at
     * the current version (see {@link DocumentReadGuard}). This prevents the model from blindly
     * overwriting content it has never seen.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param title new title (null to keep current)
     * @param description new content (null to keep current)
     * @return updated document
     */
    @Tool(
            description =
                    "Update document title and/or content. Content replaces the whole text: prefer editDocument for a fragment, updateDocumentSection for a section. Read document (getDocument) first if changing content.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocument(
            ToolContext context,
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(description = "New title (null to keep current).", required = false) @Nullable String title,
            @ToolParam(
                            description = "New content (null to keep current). Link other knowledge "
                                    + "base documents as [Title](/?doc=ID).",
                            required = false)
                    @Nullable
                    String description) {
        final long id = requireId(documentId, "documentId");

        log.debug("updateDocument called: id={} title='{}'", id, title);

        // Both fields optional by design ("null to keep current") — but a call that fills in
        // neither changes nothing at all, which is a dropped instruction rather than a no-op.
        if (title == null && description == null) {
            throw new IllegalArgumentException(
                    "Neither 'title' nor 'description' was given, so there is nothing to update. "
                            + "Call the tool again with the field you want to change.");
        }
        if (description != null) {
            readGuard.requireDocumentRead(context, id);
        }

        UpdateDocumentRequest req = new UpdateDocumentRequest();
        req.setTitle(title);
        req.setDescription(description);

        return documentService.update(id, req).toDocumentShort();
    }

    /**
     * Replaces an exact fragment of a document's markdown — the document counterpart of {@code
     * editFile}, and the cheapest way to change a wording without transferring the document.
     *
     * <p>No read-before-write guard and no {@code expectedDescriptionVersion}: the exact-match
     * contract carries both. A fragment that occurs exactly once in the stored text can only have
     * come from the current text, and one that no longer occurs fails with a message telling the
     * model to re-read — which is what a version conflict would have said, one call later.
     *
     * @param documentId document id
     * @param oldString exact existing fragment, character-for-character
     * @param newString replacement; empty string deletes the fragment
     * @param replaceAll replace every occurrence instead of requiring a unique one
     * @return updated document
     */
    @Tool(description = """
                    Surgical edit of a document: replace oldString with newString in its content. \
                    oldString must appear EXACTLY once (unless replaceAll=true) and match \
                    character-for-character, including whitespace and line breaks. \
                    No prior read required — the exact match is the safety check. \
                    For a whole rewrite use updateDocument, for a whole section updateDocumentSection.
                    """, resultConverter = CompactToolResultConverter.class)
    public DocumentShort editDocument(
            @ToolParam(description = "Document id.") Long documentId,
            @ToolParam(
                            description =
                                    "Exact existing text fragment to replace (character-for-character, including whitespace). "
                                            + "Must be unique in the document — add surrounding lines if ambiguous.")
                    String oldString,
            @ToolParam(
                            description = "New text to replace oldString. Empty string to delete the fragment. "
                                    + "Link other knowledge base documents as [Title](/?doc=ID).")
                    String newString,
            @ToolParam(
                            description =
                                    "Replace ALL occurrences of oldString (true) or exactly one (false, default).",
                            required = false)
                    @Nullable
                    Boolean replaceAll) {
        final long id = requireId(documentId, "documentId");
        // Not requireText: a fragment made only of whitespace is a legitimate (if unlikely) edit,
        // and the exactly-once rule rejects a useless one far more precisely than a blank check.
        requireContent(oldString, "oldString");
        // Empty newString deletes the fragment — documented, and the reason absent cannot mean the
        // same thing: defaulting it to "" would turn a forgotten argument into a silent deletion.
        requireContent(newString, "newString");
        final boolean all = orDefault(replaceAll, false);

        log.debug(
                "editDocument called: id={} old {} chars, new {} chars, replaceAll={}",
                id,
                oldString.length(),
                newString.length(),
                all);

        return documentService
                .patchDescription(id, current -> ExactEdit.replace(
                                current,
                                // The stored text keeps whatever line endings it
                                // has (a document imported from Windows has CRLF),
                                // so the fragments are brought to those rather than
                                // the body rewritten to the fragments'.
                                ExactEdit.alignLineEndings(current, oldString),
                                ExactEdit.alignLineEndings(current, newString),
                                all,
                                "document id=" + id,
                                "getDocument")
                        .text())
                .toDocumentShort();
    }

    /**
     * Replays a write that {@link DocumentReadGuard} refused, with the arguments the model sent the
     * first time — taken from the run's {@link ToolInvocationCollector}, where the refused call is
     * kept whole ({@code argumentsRaw}). What it saves is the model resending a document's worth of
     * text only to prove it has now read the document.
     *
     * <p>The replay goes through the same tool method, guard included: it passes only once the read
     * the refusal asked for has happened. Only calls of this response can be replayed, and each
     * only once — see {@link DocumentReadGuard#refusedWrite}.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param callRef the call reference named in the refusal
     * @return the result of the replayed write
     */
    @Tool(description = """
                    Repeat a document write that was refused because the document had not been \
                    read — with exactly the arguments of that call, so its content need not be \
                    sent again. First make the read the refusal asks for. callRef comes from the \
                    refusal; only calls of this same response can be repeated, each once.
                    """, resultConverter = CompactToolResultConverter.class)
    public DocumentShort retryDocumentWrite(
            ToolContext context, @ToolParam(description = "callRef from the refusal message.") String callRef) {
        final String ref = requireText(callRef, "callRef");
        final DocumentReadGuard.RefusedWrite refused = DocumentReadGuard.refusedWrite(context, ref);

        log.debug("retryDocumentWrite called: callRef={} tool={}", ref, refused.tool());

        final long id = requireId(refused.argument("documentId", Object.class), "documentId");
        return switch (refused.tool()) {
            case "updateDocument" ->
                updateDocument(
                        context,
                        id,
                        refused.argument("title", String.class),
                        refused.argument("description", String.class));
            case "updateDocumentSection" ->
                updateDocumentSection(
                        context,
                        id,
                        refused.text("sectionPath"),
                        refused.content("newContent"),
                        refused.expectedVersion());
            case "insertDocumentSection" ->
                insertDocumentSection(
                        context,
                        id,
                        refused.text("anchorSectionPath"),
                        requireValue(refused.argument("position", InsertPosition.class), "position"),
                        refused.content("newContent"),
                        refused.expectedVersion());
            case "deleteDocumentSection" ->
                deleteDocumentSection(context, id, refused.text("sectionPath"), refused.expectedVersion());
            case "renameDocumentSections" ->
                renameDocumentSections(context, id, refused.renames(), refused.expectedVersion());
            default -> throw new IllegalStateException("not a guarded write: " + refused.tool());
        };
    }

    /** Rejects section content that does not start with an ATX markdown heading. */
    private static void requireStartsWithHeading(String content) {
        if (!content.strip().matches("(?s)#{1,6}[ \\t].*")) {
            throw new IllegalArgumentException("Текст секции должен начинаться с markdown-заголовка (например "
                    + "'## Название') — секция включает заголовок.");
        }
    }

    //    /**
    //     * Deletes a document or folder (and all its descendants). System documents cannot be
    // deleted.
    //     *
    //     * @param id document id
    //     * @return confirmation message
    //     */
    //    @Tool(
    //            description =
    //                    "Удалить документ или папку по id (вместе со всеми дочерними узлами). "
    //                            + "Системные документы удалить нельзя.")
    //    public String deleteDocument(
    //            @ToolParam(description = "ID документа или папки для удаления") String id) {
    //        log.debug("deleteDocument called: id={}", id);
    //        documentService.delete(id);
    //        return "Документ id=" + id + " успешно удалён.";
    //    }

    /**
     * Copies an attachment from the current chat conversation to a knowledge-base document. This
     * allows users to persist useful files from chat into the permanent knowledge base.
     *
     * @param context tool context (provides conversation id)
     * @param attachmentId id of the attachment to copy
     * @param targetDocumentId id of the target document to attach the file to
     * @return confirmation message with new attachment id
     */
    @Tool(description = """
                    Скопировать вложение из текущего чата в документ базы знаний. Используй, \
                    когда пользователь хочет сохранить файл из чата в документ.""", resultConverter = CompactToolResultConverter.class)
    public String copyAttachmentToDocument(
            ToolContext context,
            @ToolParam(description = "ID вложения из чата") Long attachmentId,
            @ToolParam(description = "ID целевого документа в базе знаний") Long targetDocumentId) {
        final long sourceId = requireId(attachmentId, "attachmentId");
        final long targetId = requireId(targetDocumentId, "targetDocumentId");

        final String conversationId = conversationId(context);
        log.debug(
                "[{}] copyAttachmentToDocument called: attachmentId={} targetDocumentId={}",
                conversationId,
                sourceId,
                targetId);

        var newAttachment = attachmentService.copyToDocument(sourceId, targetId);

        return "Вложение '"
                + newAttachment.fileName()
                + "' скопировано в документ id="
                + targetDocumentId
                + " (новый id вложения: "
                + newAttachment.id()
                + ").";
    }
}
