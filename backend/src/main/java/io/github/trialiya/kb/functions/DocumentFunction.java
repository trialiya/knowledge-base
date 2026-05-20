package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.service.DocumentService;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Spring AI tools that give the chat model read-only access to the knowledge-base.
 *
 * <p>Four capabilities are exposed:
 *
 * <ul>
 *   <li>{@link #searchDocuments} — hybrid search (keyword + semantic) with configurable params.
 *   <li>{@link #findDocumentsByName} — lookup by title (exact or partial match).
 *   <li>{@link #getTreeSkeleton} — lightweight flat list of all nodes (id/title/type only).
 *   <li>{@link #getDocument} — full content of a single node by id.
 * </ul>
 *
 * <p>Register as a bean and pass to the chat model's tool callbacks, the same way {@link
 * TopicFunction} is wired up.
 */
@Slf4j
@AllArgsConstructor
public class DocumentFunction {

    private final DocumentService documentService;

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Hybrid search across the knowledge base (keyword + semantic).
     *
     * <p>Use this to find documents or folders relevant to the user's question. Prefer this over
     * {@link #getDocumentTree} when looking for specific content.
     *
     * @param query natural-language or keyword search string
     * @param mode search mode: "hybrid" (default), "semantic", or "keyword"
     * @param threshold minimum cosine similarity for semantic/hybrid (0..1); null → config default
     * @param limit maximum number of results; null → config default
     * @param kwWeight keyword score weight for hybrid mode (0..1); null → config default
     * @param semWeight semantic score weight for hybrid mode (0..1); null → config default
     * @return list of matching documents with title, snippet, and update time
     */
    @Tool(
            description =
                    "Поиск документов и папок в базе знаний (гибридный поиск: keyword + semantic)")
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
                    "Получить структуру базы знаний: id, title, type, parentId всех узлов (без содержимого)")
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
                    "Найти документы или папки по названию (точное или частичное совпадение). "
                            + "Возвращает список: сначала точные совпадения, затем частичные. "
                            + "Используй когда знаешь имя документа и хочешь получить его id или содержимое.")
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
     * @param id document or folder id (from {@link #getTreeSkeleton} results)
     * @return document node with description, updatedAt, and direct children list
     */
    @Tool(
            description =
                    "Получить конкретный документ или папку по id, включая полное содержимое (description) и список прямых дочерних узлов")
    public DocumentNode getDocument(@ToolParam(description = "ID документа или папки") String id) {
        log.info("getDocument called: id={}", id);
        return documentService.getById(Long.parseLong(id));
    }
}
