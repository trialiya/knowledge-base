package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.model.attachment.entity.AttachmentEmbeddingEntity;
import io.github.trialiya.kb.model.attachment.entity.AttachmentEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.AttachmentEmbeddingRepository;
import io.github.trialiya.kb.repository.AttachmentRepository;
import io.github.trialiya.kb.repository.DocumentEmbeddingRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import io.github.trialiya.kb.repository.EmbeddingTaskRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the pgvector index for documents <b>and attachments</b>, and exposes semantic search
 * across both.
 *
 * <h3>Indexing</h3>
 *
 * <ul>
 *   <li>{@link #indexDocument} – enqueues an embedding task for one document (Transactional Outbox:
 *       joins the caller's TX so the task is never visible if the document save rolls back).
 *   <li>{@link #reindexAll} – enqueues tasks for every document and attachment; returns quickly.
 *   <li>{@link #indexDocumentById} / {@link #indexAttachmentById} – called by {@link
 *       EmbeddingTaskScheduler} workers: load entity, call AI, persist vector.
 *   <li>{@link #deleteIndex} – removes the document embedding.
 * </ul>
 *
 * <h3>Search</h3>
 *
 * <ul>
 *   <li>{@link #search(String, double, int)} – documents only (backward compat).
 *   <li>{@link #searchAll(String, double, int)} – documents + attachments merged by similarity.
 * </ul>
 */
@Slf4j
@Service
public class SemanticSearchService {

    private final boolean enabled;

    private final double defaultThreshold;
    private final int defaultLimit;

    private final EmbeddingService embeddingService;
    private final EmbeddingTaskRepository taskRepo;
    private final DocumentEmbeddingRepository embeddingRepo;
    private final DocumentRepository documentRepo;
    private final AttachmentEmbeddingRepository attachmentEmbeddingRepo;
    private final AttachmentRepository attachmentRepo;

    public SemanticSearchService(
            EmbeddingService embeddingService,
            EmbeddingTaskRepository taskRepo,
            DocumentEmbeddingRepository embeddingRepo,
            DocumentRepository documentRepo,
            AttachmentEmbeddingRepository attachmentEmbeddingRepo,
            AttachmentRepository attachmentRepo,
            SearchConfiguration searchConfig) {
        this.embeddingService = embeddingService;
        this.taskRepo = taskRepo;
        this.embeddingRepo = embeddingRepo;
        this.documentRepo = documentRepo;
        this.attachmentEmbeddingRepo = attachmentEmbeddingRepo;
        this.attachmentRepo = attachmentRepo;
        this.enabled = searchConfig.semantic().enabled();
        this.defaultThreshold = searchConfig.semantic().threshold();
        this.defaultLimit = searchConfig.semantic().limit();
    }

    // ── Document indexing ─────────────────────────────────────────────────────

    /**
     * Enqueues an embedding task for a document.
     *
     * <p>Joins the caller's transaction (Transactional Outbox): the task row is only committed if
     * the surrounding document save also commits. If no outer TX exists a new one is created.
     */
    @Transactional
    public void indexDocument(Long documentId, String title, String description) {
        if (!enabled) {
            return;
        }
        taskRepo.enqueueIfAbsent("document", documentId);
        log.debug("Queued embedding task for document id={}", documentId);
    }

    @Transactional
    public void deleteIndex(Long documentId) {
        if (!enabled) {
            return;
        }
        embeddingRepo.deleteByDocumentId(documentId);
        log.debug("Removed embedding for document id={}", documentId);
    }

    // ── Worker entry points (called by EmbeddingTaskScheduler) ───────────────

    /**
     * Loads a document, calls the embedding API, and persists the vector. Runs on a virtual thread
     * outside any database transaction.
     */
    public void indexDocumentById(Long documentId) {
        Optional<DocumentEntity> found = documentRepo.findById(documentId);
        if (found.isEmpty()) {
            // Deleted after the task was enqueued — nothing to embed, treat as done.
            log.debug("Document id={} no longer exists, skipping embedding", documentId);
            return;
        }
        DocumentEntity doc = found.get();

        EmbeddingResponse response =
                embeddingService.embedDocument(doc.getTitle(), doc.getDescription());
        if (response.getResults().isEmpty()) {
            return;
        }
        upsertDocumentEmbedding(documentId, response.getResult().getOutput());
        log.debug("Indexed document id={} title='{}'", documentId, doc.getTitle());
    }

    /**
     * Loads an attachment, calls the embedding API, and persists the vector. Runs on a virtual
     * thread outside any database transaction.
     */
    public void indexAttachmentById(Long attachmentId) {
        Optional<AttachmentEntity> found = attachmentRepo.findById(attachmentId);
        if (found.isEmpty()) {
            // Deleted after the task was enqueued — nothing to embed, treat as done.
            log.debug("Attachment id={} no longer exists, skipping embedding", attachmentId);
            return;
        }
        AttachmentEntity att = found.get();

        String text = buildAttachmentText(att);
        EmbeddingResponse response = embeddingService.embed(text);
        if (response.getResults().isEmpty()) {
            return;
        }
        upsertAttachmentEmbedding(attachmentId, response.getResult().getOutput());
        log.debug("Indexed attachment id={} fileName='{}'", attachmentId, att.getFileName());
    }

    // ── Reindex all (documents + attachments) ─────────────────────────────────

    /**
     * Enqueues embedding tasks for every document and every attachment. Returns immediately; actual
     * re-embedding happens in background via {@link EmbeddingTaskScheduler}.
     *
     * @return total number of tasks enqueued
     */
    public int reindexAll() {
        if (!enabled) {
            return 0;
        }
        int docCount = enqueueAllDocuments();
        int attCount = enqueueAllAttachments();
        log.info("Reindex queued: {} documents + {} attachments", docCount, attCount);
        return docCount + attCount;
    }

    private int enqueueAllDocuments() {
        List<DocumentEntity> all = new ArrayList<>();
        documentRepo.findAll().forEach(all::add);
        all.forEach(doc -> taskRepo.enqueueIfAbsent("document", doc.getId()));
        return all.size();
    }

    private int enqueueAllAttachments() {
        List<AttachmentEntity> all = new ArrayList<>();
        attachmentRepo.findAll().forEach(all::add);
        all.forEach(att -> taskRepo.enqueueIfAbsent("attachment", att.getId()));
        return all.size();
    }

    // ── Search (documents only — backward compat) ─────────────────────────────

    public List<SemanticSearchResult> search(String query, double threshold, int limit) {
        if (!enabled) {
            return List.of();
        }
        float[] queryVector = embeddingService.embed(query).getResult().getOutput();
        return embeddingRepo.findSimilar(queryVector, threshold, limit);
    }

    public List<SemanticSearchResult> search(String query) {
        return search(query, defaultThreshold, defaultLimit);
    }

    // ── Search (documents + attachments) ──────────────────────────────────────

    /**
     * Searches both documents and attachments by vector similarity and merges the results, sorted
     * by descending similarity.
     */
    public List<SemanticSearchResult> searchAll(String query, double threshold, int limit) {
        if (!enabled) {
            return List.of();
        }
        float[] queryVector = embeddingService.embed(query).getResult().getOutput();

        List<SemanticSearchResult> docResults =
                embeddingRepo.findSimilar(queryVector, threshold, limit);
        List<SemanticSearchResult> attResults =
                attachmentEmbeddingRepo.findSimilar(queryVector, threshold, limit);

        List<SemanticSearchResult> merged = new ArrayList<>(docResults.size() + attResults.size());
        merged.addAll(docResults);
        merged.addAll(attResults);
        merged.sort(Comparator.comparingDouble(SemanticSearchResult::similarity).reversed());

        return merged.stream().limit(limit).toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Runs in autocommit on a worker thread by design: the find+save pair needs no atomicity
    // (upsert is idempotent, last writer wins) and must not hold a connection during AI calls.
    private void upsertDocumentEmbedding(Long documentId, float[] vector) {
        DocumentEmbeddingEntity entity =
                embeddingRepo
                        .findByDocumentId(documentId)
                        .orElseGet(
                                () -> {
                                    DocumentEmbeddingEntity e = new DocumentEmbeddingEntity();
                                    e.setDocumentId(documentId);
                                    return e;
                                });
        entity.setEmbedding(vector);
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setModel(embeddingService.getModelName());
        embeddingRepo.save(entity);
    }

    private void upsertAttachmentEmbedding(Long attachmentId, float[] vector) {
        AttachmentEmbeddingEntity entity =
                attachmentEmbeddingRepo
                        .findByAttachmentId(attachmentId)
                        .orElseGet(
                                () -> {
                                    AttachmentEmbeddingEntity e = new AttachmentEmbeddingEntity();
                                    e.setAttachmentId(attachmentId);
                                    return e;
                                });
        entity.setEmbedding(vector);
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setModel(embeddingService.getModelName());
        attachmentEmbeddingRepo.save(entity);
    }

    private String buildAttachmentText(AttachmentEntity att) {
        StringBuilder sb = new StringBuilder(att.getFileName());
        if (att.getSummary() != null && !att.getSummary().isBlank()) {
            sb.append('\n').append(att.getSummary());
        }
        if (att.getContent() != null && !att.getContent().isBlank()) {
            String content = att.getContent();
            if (content.length() > 6000) content = content.substring(0, 6000);
            sb.append('\n').append(content);
        }
        return sb.toString();
    }
}
