package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.model.attachment.entity.AttachmentEmbeddingEntity;
import io.github.trialiya.kb.model.attachment.entity.AttachmentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.AttachmentEmbeddingRepository;
import io.github.trialiya.kb.repository.AttachmentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the full lifecycle of text-file attachments: upload, retrieval, semantic indexing, and
 * AI-powered summarization.
 *
 * <h3>Upload flow</h3>
 *
 * <ol>
 *   <li>Validate that the file is text-based (MIME type starts with {@code text/}).
 *   <li>Read content as UTF-8 and persist an {@link AttachmentEntity}.
 *   <li>Asynchronously embed the content via {@link EmbeddingService} and store the vector in
 *       {@link AttachmentEmbeddingEntity} for semantic search.
 * </ol>
 *
 * <h3>Summarization</h3>
 *
 * Triggered explicitly by the user. The LLM receives the file content and produces a concise
 * description that is stored in {@link AttachmentEntity#getSummary()} and re-indexed.
 */
@Slf4j
@Lazy
@Service
public class AttachmentService implements DisposableBean {

    private static final int PROMPT_MAX_CHARS = 12_000;
    private static final String SUMMARIZE_PROMPT =
            """
        Создай краткое описание содержимого файла "{fileName}".
        Описание должно быть информативным: тип контента, основные темы, \
        ключевые сущности, структура. 2-4 предложения.
        Используй простой текст без форматирования.

        Содержимое файла:
        ```
        {content}
        ```
        """;

    private final AttachmentRepository attachmentRepo;
    private final AttachmentEmbeddingRepository embeddingRepo;
    private final EmbeddingService embeddingService;
    private final ChatClient chatClient;
    private final ExecutorService indexingExecutor;

    public AttachmentService(
            AttachmentRepository attachmentRepo,
            AttachmentEmbeddingRepository embeddingRepo,
            EmbeddingService embeddingService,
            OpenAiChatModel openAiChatModel) {
        this.attachmentRepo = attachmentRepo;
        this.embeddingRepo = embeddingRepo;
        this.embeddingService = embeddingService;
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.indexingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void destroy() {
        indexingExecutor.shutdown();
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads a text file and attaches it to a document.
     *
     * @param documentId owning document id
     * @param file multipart file (must be text-based)
     * @return persisted attachment DTO
     */
    @Transactional
    public Attachment uploadForDocument(Long documentId, MultipartFile file) {
        return upload("document", documentId, null, file);
    }

    /**
     * Uploads a text file and attaches it to a chat conversation.
     *
     * @param conversationId owning conversation id
     * @param file multipart file (must be text-based)
     * @return persisted attachment DTO
     */
    @Transactional
    public Attachment uploadForChat(String conversationId, MultipartFile file) {
        return upload("chat", null, conversationId, file);
    }

    /**
     * Copies an existing attachment (typically from a chat conversation) to a document in the
     * knowledge base. Creates a new {@link AttachmentEntity} with {@code ownerType = "document"}
     * and triggers async embedding indexing.
     *
     * @param attachmentId source attachment id
     * @param targetDocumentId target document id
     * @return the newly created attachment DTO
     */
    @Transactional
    public Attachment copyToDocument(Long attachmentId, Long targetDocumentId) {
        AttachmentEntity source = findOrThrow(attachmentId);

        OffsetDateTime now = OffsetDateTime.now();
        AttachmentEntity copy = new AttachmentEntity();
        copy.setOwnerType("document");
        copy.setDocumentId(targetDocumentId);
        copy.setConversationId(null);
        copy.setFileName(source.getFileName());
        copy.setContentType(source.getContentType());
        copy.setFileSize(source.getFileSize());
        copy.setContent(source.getContent());
        copy.setSummary(source.getSummary());
        copy.setSourceUrl(source.getSourceUrl());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);

        AttachmentEntity saved = attachmentRepo.save(copy);
        log.info(
                "Copied attachment id={} -> new id={} for document={}",
                attachmentId,
                saved.getId(),
                targetDocumentId);

        indexAsync(saved.getId());

        return toDto(saved);
    }

    // ── Retrieval ─────────────────────────────────────────────────────────────

    public List<Attachment> findByDocument(Long documentId) {
        return attachmentRepo.findByDocumentId(documentId).stream().map(this::toDto).toList();
    }

    public List<Attachment> findByConversation(String conversationId) {
        return attachmentRepo.findByConversationId(conversationId).stream()
                .map(this::toDto)
                .toList();
    }

    public long countByConversation(String conversationId) {
        return attachmentRepo.countByConversationId(conversationId);
    }

    public List<Attachment> getByFileName(String conversationId, String fileName) {
        return attachmentRepo.findByConversationId(conversationId).stream()
                .filter(attachment -> attachment.getFileName().contains(fileName))
                .map(this::toDto)
                .toList();
    }

    public Attachment getById(Long id) {
        return toDto(findOrThrow(id));
    }

    /** Returns the raw text content of an attachment (for download / AI tool). */
    public String getContent(Long id) {
        AttachmentEntity entity = findOrThrow(id);
        return entity.getContent();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(Long id) {
        AttachmentEntity entity = findOrThrow(id);
        embeddingRepo.deleteByAttachmentId(id);
        attachmentRepo.delete(entity);
        log.info("Deleted attachment id={} fileName='{}'", id, entity.getFileName());
    }

    // ── Summarize ─────────────────────────────────────────────────────────────

    /**
     * Generates an AI summary for the given attachment and persists it. Re-indexes the embedding
     * using the summary text for better search relevance.
     *
     * @param id attachment id
     * @return updated attachment DTO with summary populated
     */
    @Transactional
    public Attachment summarize(Long id) {
        AttachmentEntity entity = findOrThrow(id);
        if (entity.getContent() == null || entity.getContent().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Attachment has no text content to summarize");
        }

        String truncated = truncateForPrompt(entity.getContent(), PROMPT_MAX_CHARS);

        String summaryText =
                chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(SUMMARIZE_PROMPT)
                                                .param("fileName", entity.getFileName())
                                                .param("content", truncated))
                        .call()
                        .content();

        entity.setSummary(summaryText);
        entity.setUpdatedAt(OffsetDateTime.now());
        attachmentRepo.save(entity);

        // Re-index asynchronously with updated summary
        indexAsync(entity.getId());

        log.info("Summarized attachment id={} fileName='{}'", id, entity.getFileName());
        return toDto(entity);
    }

    // ── Semantic search ───────────────────────────────────────────────────────

    /**
     * Searches attachments by vector similarity.
     *
     * @param query natural-language query
     * @param threshold minimum cosine similarity (0..1)
     * @param limit max results
     * @return matching attachments ordered by relevance
     */
    public List<SemanticSearchResult> semanticSearch(String query, double threshold, int limit) {
        float[] queryVector = embeddingService.embed(query).getResult().getOutput();
        return embeddingRepo.findSimilar(queryVector, threshold, limit);
    }

    // ── Keyword search ────────────────────────────────────────────────────────

    public List<Attachment> search(String conversationId, String q) {
        return attachmentRepo.search(q).stream().map(this::toDto).toList();
    }

    public List<Attachment> search(String q) {
        return attachmentRepo.search(q).stream().map(this::toDto).toList();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Attachment upload(
            String ownerType, Long documentId, String conversationId, MultipartFile file) {
        validateTextFile(file);

        String content;
        try (InputStream is = file.getInputStream()) {
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file", e);
        }

        OffsetDateTime now = OffsetDateTime.now();
        AttachmentEntity entity = new AttachmentEntity();
        entity.setOwnerType(ownerType);
        entity.setDocumentId(documentId);
        entity.setConversationId(conversationId);
        entity.setFileName(file.getOriginalFilename());
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "text/plain");
        entity.setFileSize(file.getSize());
        entity.setContent(content);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        AttachmentEntity saved = attachmentRepo.save(entity);
        log.info(
                "Uploaded attachment id={} owner={}:{} fileName='{}' size={}",
                saved.getId(),
                ownerType,
                ownerType.equals("document") ? documentId : conversationId,
                saved.getFileName(),
                saved.getFileSize());

        // Index asynchronously — does not block the upload response
        indexAsync(saved.getId());

        return toDto(saved);
    }

    /**
     * Submits an embedding indexing task to a virtual-thread executor. The caller returns
     * immediately; the embedding API call happens on a separate virtual thread.
     *
     * <p>Unlike {@code @Async}, this works correctly for self-invocation within the same bean
     * because it bypasses Spring AOP proxying entirely.
     *
     * @param attachmentId the attachment to index
     */
    public void indexAsync(Long attachmentId) {
        indexingExecutor.submit(
                () -> {
                    try {
                        AttachmentEntity entity =
                                attachmentRepo
                                        .findById(attachmentId)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Attachment not found for indexing: "
                                                                        + attachmentId));
                        doIndex(entity);
                        log.debug("Async indexing completed for attachment id={}", attachmentId);
                    } catch (Exception ex) {
                        log.warn(
                                "Async embedding index failed for attachment id={}: {}",
                                attachmentId,
                                ex.getMessage());
                    }
                });
    }

    /**
     * Performs the actual embedding index operation. Called on a virtual thread from {@link
     * #indexAsync}.
     */
    private void doIndex(AttachmentEntity entity) {
        String textToEmbed = buildEmbeddingText(entity);
        EmbeddingResponse resp = embeddingService.embed(textToEmbed);

        AttachmentEmbeddingEntity emb =
                embeddingRepo
                        .findByAttachmentId(entity.getId())
                        .orElseGet(
                                () -> {
                                    AttachmentEmbeddingEntity e = new AttachmentEmbeddingEntity();
                                    e.setAttachmentId(entity.getId());
                                    return e;
                                });

        emb.setEmbedding(resp.getResult().getOutput());
        emb.setModel(embeddingService.getModelName());
        emb.setUpdatedAt(OffsetDateTime.now());
        embeddingRepo.save(emb);

        log.debug("Indexed attachment id={}", entity.getId());
    }

    /**
     * Builds the text used for embedding. If a summary exists it is prepended for better retrieval
     * quality; otherwise the raw content is used (truncated to fit the token budget).
     */
    private String buildEmbeddingText(AttachmentEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity.getFileName());
        if (entity.getSummary() != null && !entity.getSummary().isBlank()) {
            sb.append('\n').append(entity.getSummary());
        }
        if (entity.getContent() != null && !entity.getContent().isBlank()) {
            sb.append('\n').append(truncateForPrompt(entity.getContent(), 6_000));
        }
        return sb.toString();
    }

    private void validateTextFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        log.info("Loading file: {} ({})", file.getOriginalFilename(), file.getContentType());
        String ct = file.getContentType();
        if (ct != null && !ct.startsWith("text/") && !isKnownTextType(ct)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only text-based files are supported. Got: " + ct);
        }
    }

    /** Accept common text-ish MIME types that don't start with "text/". */
    private boolean isKnownTextType(String contentType) {
        return contentType.equals("application/json")
                || contentType.equals("application/xml")
                || contentType.equals("application/yaml")
                || contentType.equals("application/x-yaml")
                || contentType.equals("application/octet-stream")
                || contentType.equals("application/javascript")
                || contentType.equals("application/typescript")
                || contentType.equals("application/sql")
                || contentType.equals("application/x-sh")
                || contentType.equals("application/xhtml+xml");
    }

    private AttachmentEntity findOrThrow(Long id) {
        return attachmentRepo
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Attachment not found: " + id));
    }

    private Attachment toDto(AttachmentEntity e) {
        return new Attachment(
                e.getId(),
                e.getOwnerType(),
                e.getDocumentId(),
                e.getConversationId(),
                e.getFileName(),
                e.getContentType(),
                e.getFileSize(),
                e.getSummary(),
                e.getSourceUrl(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    /** Truncates text to approximately {@code maxChars} on a word boundary. */
    private static String truncateForPrompt(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int cut = text.lastIndexOf(' ', maxChars);
        if (cut <= 0) cut = maxChars;
        return text.substring(0, cut) + "\n... (truncated)";
    }
}
