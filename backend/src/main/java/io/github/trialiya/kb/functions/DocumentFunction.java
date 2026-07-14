package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.DocumentOutline;
import io.github.trialiya.kb.model.doc.dto.DocumentSection;
import io.github.trialiya.kb.model.doc.dto.DocumentShort;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.utils.MarkdownSections;
import java.util.List;
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
                    "Поиск документов и папок в базе знаний (гибридный поиск: keyword + semantic)",
            resultConverter = CompactToolResultConverter.class)
    public List<SearchResult> searchDocuments(
            @ToolParam(description = "Поисковый запрос на любом языке") String query,
            @ToolParam(
                            description = "Режим поиска: hybrid (по умолчанию), semantic, keyword",
                            required = false)
                    @Nullable String mode,
            @ToolParam(
                            description =
                                    "Минимальный порог схожести для семантического поиска (0.0–1.0)",
                            required = false)
                    @Nullable Double threshold,
            @ToolParam(description = "Максимальное количество результатов", required = false)
                    @Nullable Integer limit,
            @ToolParam(
                            description = "Вес ключевых слов в гибридном поиске (0.0–1.0)",
                            required = false)
                    @Nullable Double kwWeight,
            @ToolParam(description = "Вес семантики в гибридном поиске (0.0–1.0)", required = false)
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
                    "Получить структуру базы знаний: id, title, type, parentId всех узлов (без содержимого)",
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
                    "Найти документы или папки по названию (точное или частичное совпадение, "
                            + "сначала точные). Матчит ТОЛЬКО title, не содержимое.",
            resultConverter = CompactToolResultConverter.class)
    public List<DocumentNode> findDocumentsByName(
            @ToolParam(description = "Полное или частичное название документа/папки") String name) {
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
                    "Получить конкретный документ или папку по id, включая полное содержимое "
                            + "(description) и список прямых дочерних узлов",
            resultConverter = CompactToolResultConverter.class)
    public DocumentNode getDocument(
            @ToolParam(description = "ID документа или папки") String documentId) {
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
            description =
                    "Получить оглавление markdown-документа: секции (sectionPath, уровень, "
                            + "заголовок, размер в символах) без содержимого. Для больших "
                            + "документов используй его, чтобы читать и править только нужные "
                            + "секции через getDocumentSection / updateDocumentSection.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentOutline getDocumentOutline(
            @ToolParam(description = "ID документа") String documentId) {
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
                    "Прочитать одну секцию markdown-документа по sectionPath из getDocumentOutline "
                            + "(заголовок + содержимое + подсекции). Экономит контекст по "
                            + "сравнению с getDocument.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentSection getDocumentSection(
            @ToolParam(description = "ID документа") String documentId,
            @ToolParam(
                            description =
                                    "Путь секции из getDocumentOutline, например "
                                            + "\"Установка > Docker\"")
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
     *       in the same chat-response session, unless {@code forceOverwrite=true}.
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
     * @param forceOverwrite skip the read-before-write check (intentional blind replace)
     * @return updated document
     */
    @Tool(
            description =
                    "Заменить одну секцию markdown-документа (заголовок + содержимое + "
                            + "подсекции), не передавая весь документ. sectionPath и "
                            + "expectedDescriptionVersion бери из getDocumentOutline / "
                            + "getDocumentSection. Перед правкой секция должна быть прочитана "
                            + "через getDocumentSection (или весь документ через getDocument) в "
                            + "этом же ответе; forceOverwrite=true пропускает проверку.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocumentSection(
            ToolContext context,
            @ToolParam(description = "ID документа") long documentId,
            @ToolParam(
                            description =
                                    "Путь секции из getDocumentOutline. Спец-секция _preamble — "
                                            + "текст до первого заголовка.")
                    String sectionPath,
            @ToolParam(
                            description =
                                    "Новый текст секции целиком, начиная с её markdown-заголовка "
                                            + "(например '## Название'). Ссылки на другие "
                                            + "документы оформляй как [Название](/?doc=ID).")
                    String newContent,
            @ToolParam(
                            description =
                                    "descriptionVersion из getDocumentOutline/getDocumentSection; "
                                            + "при несовпадении с текущей версией правка "
                                            + "отклоняется")
                    int expectedDescriptionVersion,
            @ToolParam(
                            description =
                                    "true — заменить секцию без предварительного чтения "
                                            + "(пропускает проверку)",
                            required = false)
                    @Nullable Boolean forceOverwrite) {

        log.info(
                "updateDocumentSection called: id={} sectionPath='{}' expectedDescVer={} "
                        + "forceOverwrite={}",
                documentId,
                sectionPath,
                expectedDescriptionVersion,
                forceOverwrite);

        if (!Boolean.TRUE.equals(forceOverwrite)) {
            requireSectionReadInThisResponse(context, documentId, sectionPath);
        }
        if (newContent.isBlank()) {
            throw new IllegalArgumentException(
                    "newContent пуст. Передай полный новый текст секции, начиная с её заголовка.");
        }
        if (!MarkdownSections.PREAMBLE_PATH.equals(sectionPath)
                && !newContent.strip().matches("(?s)#{1,6}[ \\t].*")) {
            throw new IllegalArgumentException(
                    "newContent должен начинаться с markdown-заголовка секции (например "
                            + "'## Название') — заменяется вся секция вместе с заголовком.");
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
            description =
                    "Создать новый документ или папку в базе знаний. "
                            + "Укажи title, type (document или folder), parentId (или null для корня) "
                            + "и description (содержимое).",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort createDocument(
            @ToolParam(description = "Название документа или папки") String title,
            @ToolParam(description = "Тип: 'document' или 'folder'", required = false)
                    @Nullable String type,
            @ToolParam(
                            description =
                                    "ID родительской папки (null или пусто для корневого уровня)",
                            required = false)
                    @Nullable Long parentId,
            @ToolParam(
                            description =
                                    "Содержимое документа (текст, markdown). "
                                            + "Ссылки на другие документы базы знаний оформляй как [Название](/?doc=ID).",
                            required = false)
                    @Nullable String description) {

        log.info("createDocument called: title='{}' type={} parentId={}", title, type, parentId);

        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(title);
        req.setType(type != null && !type.isBlank() ? type : "document");
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
     * blindly overwriting content it has never seen. Pass {@code forceOverwrite=true} to skip the
     * check when a full rewrite is intended.
     *
     * @param context tool context (provides the per-response tool invocation log)
     * @param documentId document id
     * @param title new title (null to keep current)
     * @param description new content (null to keep current)
     * @param forceOverwrite skip the read-before-write check (intentional full rewrite)
     * @return updated document
     */
    @Tool(
            description =
                    "Обновить существующий документ: изменить название и/или содержимое. "
                            + "Передай только те поля, которые нужно изменить. "
                            + "Перед изменением содержимого документ должен быть прочитан через "
                            + "getDocument в этом же ответе. Если нужно намеренно полностью "
                            + "переписать содержимое без чтения — передай forceOverwrite=true.",
            resultConverter = CompactToolResultConverter.class)
    public DocumentShort updateDocument(
            ToolContext context,
            @ToolParam(description = "ID документа для обновления") long documentId,
            @ToolParam(
                            description = "Новое название (null чтобы оставить текущее)",
                            required = false)
                    @Nullable String title,
            @ToolParam(
                            description =
                                    "Новое содержимое (null чтобы оставить текущее). "
                                            + "Ссылки на другие документы базы знаний оформляй как [Название](/?doc=ID).",
                            required = false)
                    @Nullable String description,
            @ToolParam(
                            description =
                                    "true — намеренно переписать содержимое без предварительного "
                                            + "чтения документа (пропускает проверку)",
                            required = false)
                    @Nullable Boolean forceOverwrite) {

        log.info(
                "updateDocument called: id={} title='{}' forceOverwrite={}",
                documentId,
                title,
                forceOverwrite);

        if (description != null && !Boolean.TRUE.equals(forceOverwrite)) {
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
                                        "getDocument".equals(inv.name())
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
                            + "повтори updateDocument. Если нужно намеренно полностью переписать "
                            + "содержимое без чтения — повтори вызов с forceOverwrite=true.");
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
            ToolContext context, long documentId, String sectionPath) {
        final ToolInvocationCollector collector = ToolInvocationCollector.from(context);
        if (collector == null) {
            return;
        }
        final String id = String.valueOf(documentId);
        final boolean wasRead =
                collector.snapshot().stream()
                        .filter(
                                inv ->
                                        ToolInvocationCollector.ToolInvocationStatus.OK
                                                        == inv.status()
                                                && id.equals(
                                                        String.valueOf(
                                                                inv.arguments().get("documentId"))))
                        .anyMatch(
                                inv ->
                                        "getDocument".equals(inv.name())
                                                || ("getDocumentSection".equals(inv.name())
                                                        && sectionPath.equals(
                                                                inv.arguments()
                                                                        .get("sectionPath"))));
        if (!wasRead) {
            throw new IllegalStateException(
                    "Секция '"
                            + sectionPath
                            + "' документа id="
                            + documentId
                            + " НЕ обновлена: её содержимое не было прочитано в этом ответе. "
                            + "Сначала вызови getDocumentSection(documentId="
                            + documentId
                            + ", sectionPath=\""
                            + sectionPath
                            + "\") или getDocument(documentId="
                            + documentId
                            + "), затем повтори updateDocumentSection. Если нужно намеренно "
                            + "заменить секцию без чтения — повтори вызов с forceOverwrite=true.");
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
                    "Скопировать вложение из текущего чата в документ базы знаний. "
                            + "Используй когда пользователь хочет сохранить файл из чата в документ.",
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
