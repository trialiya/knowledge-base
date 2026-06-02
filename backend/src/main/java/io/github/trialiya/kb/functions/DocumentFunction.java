package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    String mode,
            @ToolParam(
                            description =
                                    "Минимальный порог схожести для семантического поиска (0.0–1.0)",
                            required = false)
                    Double threshold,
            @ToolParam(description = "Максимальное количество результатов", required = false)
                    Integer limit,
            @ToolParam(
                            description = "Вес ключевых слов в гибридном поиске (0.0–1.0)",
                            required = false)
                    Double kwWeight,
            @ToolParam(description = "Вес семантики в гибридном поиске (0.0–1.0)", required = false)
                    Double semWeight) {

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
    public Document createDocument(
            @ToolParam(description = "Название документа или папки") String title,
            @ToolParam(description = "Тип: 'document' или 'folder'", required = false) String type,
            @ToolParam(
                            description =
                                    "ID родительской папки (null или пусто для корневого уровня)",
                            required = false)
                    String parentId,
            @ToolParam(
                            description =
                                    "Содержимое документа (текст, markdown). "
                                            + "Ссылки на другие документы базы знаний оформляй как [Название](/?doc=ID).",
                            required = false)
                    String description) {

        log.info("createDocument called: title='{}' type={} parentId={}", title, type, parentId);

        CreateDocumentRequest req = new CreateDocumentRequest();
        req.setTitle(title);
        req.setType(type != null && !type.isBlank() ? type : "document");
        req.setParentId(parentId != null && !parentId.isBlank() ? parentId : null);
        req.setDescription(description);

        return documentService.create(req);
    }

    /**
     * Updates an existing document's title and/or content.
     *
     * @param documentId document id
     * @param title new title (null to keep current)
     * @param description new content (null to keep current)
     * @return updated document
     */
    @Tool(
            description =
                    "Обновить существующий документ: изменить название и/или содержимое. "
                            + "Передай только те поля, которые нужно изменить.",
            resultConverter = CompactToolResultConverter.class)
    public Document updateDocument(
            @ToolParam(description = "ID документа для обновления") long documentId,
            @ToolParam(
                            description = "Новое название (null чтобы оставить текущее)",
                            required = false)
                    String title,
            @ToolParam(
                            description =
                                    "Новое содержимое (null чтобы оставить текущее). "
                                            + "Ссылки на другие документы базы знаний оформляй как [Название](/?doc=ID).",
                            required = false)
                    String description) {

        log.info("updateDocument called: id={} title='{}'", documentId, title);

        UpdateDocumentRequest req = new UpdateDocumentRequest();
        req.setTitle(title);
        req.setDescription(description);

        return documentService.update(documentId, req);
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
