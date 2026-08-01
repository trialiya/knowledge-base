package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.DocumentOutline;
import io.github.trialiya.kb.model.doc.dto.DocumentSection;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.SectionRename;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.utils.MarkdownSections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
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
 *   <li>{@link #deleteDocument} — delete a document (and its descendants).
 *   <li>{@link #copyAttachmentToDocument} — copy an attachment from the current chat to a document.
 * </ul>
 */
@Slf4j
@AllArgsConstructor
public class DocumentFunction {

    private final DocumentService documentService;
    private final AttachmentService attachmentService;

    // Tool names referenced by the read-before-write guards below, kept in one place instead of
    // repeated string literals scattered across the guard methods.
    private static final String TOOL_GET_DOCUMENT = "getDocument";
    private static final String TOOL_GET_DOCUMENT_OUTLINE = "getDocumentOutline";
    private static final String TOOL_GET_DOCUMENT_SECTION = "getDocumentSection";

    /** Where {@link #insertDocumentSection} places the new section relative to its anchor. */
    public enum InsertPosition {
        BEFORE,
        AFTER;
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
            description =
                    "Search knowledge base documents by topic/keywords (hybrid: keyword + semantic).",
            resultConverter = CompactToolResultConverter.class)
    public List<SearchResult> searchDocuments(
            @ToolParam(description = "Search query in any language.") String query,
            @ToolParam(
                            description = "Search mode: hybrid (default), semantic, keyword.",
                            required = false)
                    @Nullable String mode,
            @ToolParam(
                            description =
                                    "Minimum cosine similarity for semantic/hybrid search (0.0–1.0).",
                            required = false)
                    @Nullable Double threshold,
            @ToolParam(description = "Maximum number of results.", required = false)
                    @Nullable Integer limit,
            @ToolParam(description = "Keyword weight in hybrid mode (0.0–1.0).", required = false)
                    @Nullable Double kwWeight,
            @ToolParam(description = "Semantic weight in hybrid mode (0.0–1.0).", required = false)
                    @Nullable Double semWeight) {

        String effectiveMode = (mode != null && !mode.isBlank()) ? mode.toLowerCase() : "hybrid";
        log.info(
                "Document search: query='{}' mode={} threshold={} limit={}",
                query,
                effectiveMode,
                threshold,
                limit);

        return switch (effectiveMode) {
            case "semantic" -> documentService.semanticSearch(query, threshold, limit);
            case "keyword" -> documentService.search(query);
            default -> documentService.hybridSearch(query, threshold, limit, kwWeight, semWeight);
        };
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
            description =
                    "List all knowledge base nodes (id, title, type, parentId) without content.",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentNode> getTreeSkeleton() {
        log.info("getTreeSkeleton called");
        return documentService.getTreeSkeleton();
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
     * @return list of matching nodes with id, title, type, parentId, description, hasChildren
     */
    @Tool(
            description =
                    "Find document/folder by title (exact or partial match, case-insensitive).",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentNode> findDocumentsByName(
            @ToolParam(description = "Document/folder title (full or partial).") String name) {
        log.info("findDocumentsByName called: name='{}'", name);
        return documentService.findByName(name);
    }

    // ── Single document ───────────────────────────────────────────────────────

    /**
     * Fetches a single document or folder by id, including its full description/content and a list
     * of its direct children (shallow, without their descriptions).
     *
     * @param documentId document or folder id (from {@link #getTreeSkeleton} results)
     * @return document node with description, updatedAt, and direct children list
     */
    @Tool(
            description =
                    "Read full document/folder content by id, including direct children (shallow).",
            resultConverter = CompactToolResultConverter.class)
    public DocumentNode getDocument(
            @ToolParam(description = "Document or folder id.") String documentId) {
        log.info("getDocument called: documentId={}", documentId);
        return documentService.getById(Long.parseLong(documentId));
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
    public DocumentOutline getDocumentOutline(
            @ToolParam(description = "Document id.") String documentId) {
        log.info("getDocumentOutline called: documentId={}", documentId);
        DocumentNode node = requireDocument(documentId);
        List<MarkdownSections.Section> sections = MarkdownSections.parse(descriptionOf(node));
        return new DocumentOutline(
                node.id(),
                node.title(),
                node.descriptionVersion(),
                sections.stream()
                        .map(
                                s ->
                                        new DocumentOutline.OutlineSection(
                                                s.path(),
                                                s.level(),
                                                s.title(),
                                                s.chars(),
                                                s.subsections()))
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
            description =
                    "Read one markdown section (heading + body + subsections) without full load.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentSection getDocumentSection(
            @ToolParam(description = "Document id.") String documentId,
            @ToolParam(
                            description =
                                    "Section path from getDocumentOutline (e.g., \"Setup > Docker\").")
                    String sectionPath) {
        log.info(
                "getDocumentSection called: documentId={} sectionPath='{}'",
                documentId,
                sectionPath);
        DocumentNode node = requireDocument(documentId);
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
     *       been read via {@link #getDocumentSection} (same path) or {@link #getDocument} earlier
     *       in the same chat-response session.
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
                    "Replace one markdown section. Read the section first (getDocumentSection) or full document (getDocument) in this same response. One operation per call; re-read outline afterward.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocumentSection(
            ToolContext context,
            @ToolParam(description = "Document id.") long documentId,
            @ToolParam(
                            description =
                                    "Section path from getDocumentOutline; _preamble = text before first heading.")
                    String sectionPath,
            @ToolParam(
                            description =
                                    "Full new section text, starting with its heading (e.g., \"## Title\").")
                    String newContent,
            @ToolParam(
                            description =
                                    "descriptionVersion from getDocumentOutline/getDocumentSection.")
                    int expectedDescriptionVersion) {

        log.info(
                "updateDocumentSection called: id={} sectionPath='{}' expectedDescVer={}",
                documentId,
                sectionPath,
                expectedDescriptionVersion);

        requireSectionReadInThisResponse(context, documentId, sectionPath, "updateDocumentSection");
        if (newContent.isBlank()) {
            throw new IllegalArgumentException(
                    "newContent пуст. Передай полный новый текст секции, начиная с её заголовка.");
        }
        if (!MarkdownSections.PREAMBLE_PATH.equals(sectionPath)) {
            requireStartsWithHeading(newContent);
        }

        return documentService
                .patchDescription(
                        documentId,
                        expectedDescriptionVersion,
                        current ->
                                MarkdownSections.replaceSection(
                                        current,
                                        findSectionOrThrow(current, sectionPath),
                                        newContent))
                .toDocumentShort();
    }

    /**
     * Inserts a new markdown section before or after an existing section subtree. Requires the
     * document structure to have been read in the same chat-response session ({@link
     * #getDocumentOutline}, {@link #getDocument} or {@link #getDocumentSection} of the anchor) and
     * the version check of {@link DocumentService#patchDescription}.
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
            @ToolParam(description = "Document id.") long documentId,
            @ToolParam(description = "Existing anchor section path from getDocumentOutline.")
                    String anchorSectionPath,
            @ToolParam(description = "Position: BEFORE or AFTER the anchor.")
                    InsertPosition position,
            @ToolParam(
                            description =
                                    "Full text of new section, starting with its heading (e.g., \"## Title\").")
                    String newContent,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocument.")
                    int expectedDescriptionVersion) {

        log.info(
                "insertDocumentSection called: id={} anchor='{}' position={} expectedDescVer={}",
                documentId,
                anchorSectionPath,
                position,
                expectedDescriptionVersion);

        requireStructureReadInThisResponse(context, documentId, anchorSectionPath);
        boolean before = position == InsertPosition.BEFORE;
        if (before && MarkdownSections.PREAMBLE_PATH.equals(anchorSectionPath)) {
            throw new IllegalArgumentException(
                    "Вставка before _preamble невозможна — используй after.");
        }
        requireStartsWithHeading(newContent);

        return documentService
                .patchDescription(
                        documentId,
                        expectedDescriptionVersion,
                        current ->
                                MarkdownSections.insertSection(
                                        current,
                                        findSectionOrThrow(current, anchorSectionPath),
                                        newContent,
                                        before))
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
            @ToolParam(description = "Document id.") long documentId,
            @ToolParam(
                            description =
                                    "Section path from getDocumentOutline; _preamble = text before first heading.")
                    String sectionPath,
            @ToolParam(
                            description =
                                    "descriptionVersion from getDocumentOutline/getDocumentSection.")
                    int expectedDescriptionVersion) {

        log.info(
                "deleteDocumentSection called: id={} sectionPath='{}' expectedDescVer={}",
                documentId,
                sectionPath,
                expectedDescriptionVersion);

        requireSectionReadInThisResponse(context, documentId, sectionPath, "deleteDocumentSection");

        return documentService
                .patchDescription(
                        documentId,
                        expectedDescriptionVersion,
                        current ->
                                MarkdownSections.replaceSection(
                                        current, findSectionOrThrow(current, sectionPath), ""))
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
            @ToolParam(description = "Document id.") long documentId,
            @ToolParam(description = "List of renames: {sectionPath, newTitle}.")
                    List<SectionRename> renames,
            @ToolParam(description = "descriptionVersion from getDocumentOutline/getDocument.")
                    int expectedDescriptionVersion) {

        log.info(
                "renameDocumentSections called: id={} renames={} expectedDescVer={}",
                documentId,
                renames == null ? null : renames.size(),
                expectedDescriptionVersion);

        requireStructureReadInThisResponse(context, documentId, null);
        if (renames == null || renames.isEmpty()) {
            throw new IllegalArgumentException("renames пуст. Передай хотя бы одну пару.");
        }
        if (renames.stream().map(SectionRename::sectionPath).distinct().count() != renames.size()) {
            throw new IllegalArgumentException("Пути секций в renames должны быть уникальными.");
        }
        for (SectionRename rename : renames) {
            if (MarkdownSections.PREAMBLE_PATH.equals(rename.sectionPath())) {
                throw new IllegalArgumentException("_preamble не имеет заголовка.");
            }
            String title = rename.newTitle() == null ? "" : rename.newTitle().strip();
            if (title.isBlank() || title.contains("\n") || title.startsWith("#")) {
                throw new IllegalArgumentException(
                        "newTitle для '"
                                + rename.sectionPath()
                                + "' должен быть непустой одной строкой без ведущих #.");
            }
        }

        return documentService
                .patchDescription(
                        documentId,
                        expectedDescriptionVersion,
                        current -> {
                            // Resolve every path against the same text, then splice from the
                            // bottom of the document up so a rename never shifts the offsets of
                            // the sections still to be renamed.
                            record Resolved(MarkdownSections.Section section, String newTitle) {}
                            String result = current;
                            for (Resolved r :
                                    renames.stream()
                                            .map(
                                                    rn ->
                                                            new Resolved(
                                                                    findSectionOrThrow(
                                                                            current,
                                                                            rn.sectionPath()),
                                                                    rn.newTitle().strip()))
                                            .sorted(
                                                    Comparator.comparingInt(
                                                                    (Resolved r) ->
                                                                            r.section()
                                                                                    .startOffset())
                                                            .reversed())
                                            .toList()) {
                                result =
                                        MarkdownSections.renameHeading(
                                                result, r.section(), r.newTitle());
                            }
                            return result;
                        })
                .toDocumentShort();
    }

    /** Loads a node by id or fails with a model-readable error (getById returns null quietly). */
    private DocumentNode requireDocument(String documentId) {
        DocumentNode node = documentService.getById(Long.parseLong(documentId));
        if (node == null) {
            throw new IllegalArgumentException("Документ id=" + documentId + " не найден.");
        }
        return node;
    }

    private static String descriptionOf(DocumentNode node) {
        return node.description() == null ? "" : node.description();
    }

    private static MarkdownSections.Section findSectionOrThrow(
            String markdown, String sectionPath) {
        List<MarkdownSections.Section> sections = MarkdownSections.parse(markdown);
        return sections.stream()
                .filter(s -> s.path().equals(sectionPath))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Секция '"
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
            @ToolParam(description = "Type: 'document' or 'folder'.", required = false)
                    @Nullable String type,
            @ToolParam(
                            description = "Parent folder id (null or empty for root level).",
                            required = false)
                    @Nullable Long parentId,
            @ToolParam(description = "Document content (text or markdown).", required = false)
                    @Nullable String description) {

        log.info("createDocument called: title='{}' type={} parentId={}", title, type, parentId);

        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(title);
        req.setType(
                type != null && !type.isBlank()
                        ? DocumentType.fromValue(type)
                        : DocumentType.DOCUMENT);
        req.setParentId(parentId);
        req.setDescription(description);

        return documentService.create(req).toDocumentShort();
    }

    /**
     * Updates an existing document's title and/or content.
     *
     * <p>Guard: a content update ({@code description != null}) is rejected unless this document was
     * already read via {@link #getDocument} earlier in the same chat-response session (checked
     * against the request-scoped {@link ToolInvocationCollector}). This prevents the model from
     * blindly overwriting content it has never seen.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param title new title (null to keep current)
     * @param description new content (null to keep current)
     * @return updated document
     */
    @Tool(
            description =
                    "Update document title and/or content. Read document (getDocument) first if changing content.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocument(
            ToolContext context,
            @ToolParam(description = "Document id.") long documentId,
            @ToolParam(description = "New title (null to keep current).", required = false)
                    @Nullable String title,
            @ToolParam(description = "New content (null to keep current).", required = false)
                    @Nullable String description) {

        log.info("updateDocument called: id={} title='{}'", documentId, title);

        if (description != null) {
            requireReadInThisResponse(context, documentId);
        }

        UpdateDocumentRequest req = new UpdateDocumentRequest();
        req.setTitle(title);
        req.setDescription(description);

        return documentService.update(documentId, req).toDocumentShort();
    }

    /**
     * Rejects a content update if the document was not successfully read via {@link #getDocument}
     * earlier within the same chat-response session. When no {@link ToolInvocationCollector} is
     * present in the context (background jobs, tests), the check is skipped.
     */
    private static void requireReadInThisResponse(ToolContext context, long documentId) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null) {
            return;
        }
        final String id = String.valueOf(documentId);
        final boolean wasRead =
                collector.snapshot().stream()
                        .anyMatch(
                                inv ->
                                        TOOL_GET_DOCUMENT.equals(inv.name())
                                                && ToolInvocationCollector.ToolInvocationStatus.OK
                                                        == inv.status()
                                                && id.equals(
                                                        String.valueOf(
                                                                inv.arguments()
                                                                        .get("documentId"))));
        if (!wasRead) {
            throw new IllegalStateException(
                    "Документ id="
                            + documentId
                            + " НЕ обновлён: его содержимое не было прочитано в этом ответе. "
                            + "Сначала вызови getDocument(documentId="
                            + documentId
                            + "), чтобы увидеть текущее содержимое и не потерять данные, затем "
                            + "повтори updateDocument.");
        }
    }

    /**
     * Section flavour of the read-before-write guard: the update is allowed after a successful
     * {@link #getDocumentSection} of the same document+section or a successful {@link #getDocument}
     * of the whole document within the same chat-response session. When no {@link
     * ToolInvocationCollector} is present in the context (background jobs, tests), the check is
     * skipped.
     */
    private static void requireSectionReadInThisResponse(
            ToolContext context, long documentId, String sectionPath, String retryTool) {
        final boolean wasRead =
                wasReadInThisResponse(
                        context,
                        documentId,
                        inv ->
                                TOOL_GET_DOCUMENT.equals(inv.name())
                                        || (TOOL_GET_DOCUMENT_SECTION.equals(inv.name())
                                                && sectionPath.equals(
                                                        inv.arguments().get("sectionPath"))));
        if (!wasRead) {
            throw new IllegalStateException(
                    "Секция '"
                            + sectionPath
                            + "' документа id="
                            + documentId
                            + " НЕ изменена: её содержимое не было прочитано в этом ответе. "
                            + "Сначала вызови getDocumentSection(documentId="
                            + documentId
                            + ", sectionPath=\""
                            + sectionPath
                            + "\") или getDocument(documentId="
                            + documentId
                            + "), затем повтори "
                            + retryTool
                            + ".");
        }
    }

    /**
     * Structure flavour of the read-before-write guard (insert/rename): satisfied by {@link
     * #getDocumentOutline} or {@link #getDocument} of the document, or — when {@code
     * anchorSectionPath} is given — {@link #getDocumentSection} of that section, within the same
     * chat-response session.
     */
    private static void requireStructureReadInThisResponse(
            ToolContext context, long documentId, @Nullable String anchorSectionPath) {
        final boolean wasRead =
                wasReadInThisResponse(
                        context,
                        documentId,
                        inv ->
                                TOOL_GET_DOCUMENT.equals(inv.name())
                                        || TOOL_GET_DOCUMENT_OUTLINE.equals(inv.name())
                                        || (anchorSectionPath != null
                                                && TOOL_GET_DOCUMENT_SECTION.equals(inv.name())
                                                && anchorSectionPath.equals(
                                                        inv.arguments().get("sectionPath"))));
        if (!wasRead) {
            throw new IllegalStateException(
                    "Документ id="
                            + documentId
                            + " НЕ изменён: его структура не была прочитана в этом ответе. "
                            + "Сначала вызови getDocumentOutline(documentId="
                            + documentId
                            + ") или getDocument(documentId="
                            + documentId
                            + "), затем повтори операцию.");
        }
    }

    /**
     * True if a successful read matching {@code readMatches} for this document happened earlier in
     * the same chat-response session. Without a {@link ToolInvocationCollector} in the context
     * (background jobs, tests) the guard is skipped.
     */
    private static boolean wasReadInThisResponse(
            ToolContext context, long documentId, Predicate<ToolInvocation> readMatches) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null) {
            return true;
        }
        final String id = String.valueOf(documentId);
        return collector.snapshot().stream()
                .filter(
                        inv ->
                                ToolInvocationCollector.ToolInvocationStatus.OK == inv.status()
                                        && id.equals(
                                                String.valueOf(inv.arguments().get("documentId"))))
                .anyMatch(readMatches);
    }

    /** Rejects section content that does not start with an ATX markdown heading. */
    private static void requireStartsWithHeading(String content) {
        if (!content.strip().matches("(?s)#{1,6}[ \\t].*")) {
            throw new IllegalArgumentException(
                    "Текст секции должен начинаться с markdown-заголовка (например "
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
    //        log.info("deleteDocument called: id={}", id);
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
    @Tool(
            description =
                    """
                    Скопировать вложение из текущего чата в документ базы знаний. Используй, \
                    когда пользователь хочет сохранить файл из чата в документ.""",
            resultConverter = CompactToolResultConverter.class)
    public String copyAttachmentToDocument(
            ToolContext context,
            @ToolParam(description = "ID вложения из чата") String attachmentId,
            @ToolParam(description = "ID целевого документа в базе знаний")
                    String targetDocumentId) {

        final String conversationId = conversationId(context);
        log.info(
                "[{}] copyAttachmentToDocument called: attachmentId={} targetDocumentId={}",
                conversationId,
                attachmentId,
                targetDocumentId);

        var newAttachment =
                attachmentService.copyToDocument(
                        Long.parseLong(attachmentId), Long.parseLong(targetDocumentId));

        return "Вложение '"
                + newAttachment.fileName()
                + "' скопировано в документ id="
                + targetDocumentId
                + " (новый id вложения: "
                + newAttachment.id()
                + ").";
    }
}
