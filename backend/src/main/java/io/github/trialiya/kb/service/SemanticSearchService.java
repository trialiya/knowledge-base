package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *   <li>{@link #indexDocument} – upserts the embedding for one document.
 *   <li>{@link #reindexAll} – full reindex of documents <b>and</b> attachments.
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

    private final int reindexBatchSize;
    private final double defaultThreshold;
    private final int defaultLimit;

    private final EmbeddingService embeddingService;
    private final DocumentEmbeddingRepository embeddingRepo;
    private final DocumentRepository documentRepo;
    private final AttachmentEmbeddingRepository attachmentEmbeddingRepo;
    private final AttachmentRepository attachmentRepo;

    public SemanticSearchService(
            EmbeddingService embeddingService,
            DocumentEmbeddingRepository embeddingRepo,
            DocumentRepository documentRepo,
            AttachmentEmbeddingRepository attachmentEmbeddingRepo,
            AttachmentRepository attachmentRepo,
            EmbeddingConfiguration embeddingConfig,
            SearchConfiguration searchConfig) {
        this.embeddingService = embeddingService;
        this.embeddingRepo = embeddingRepo;
        this.documentRepo = documentRepo;
        this.attachmentEmbeddingRepo = attachmentEmbeddingRepo;
        this.attachmentRepo = attachmentRepo;
        this.reindexBatchSize = embeddingConfig.reindexBatchSize();
        this.defaultThreshold = searchConfig.semantic().threshold();
        this.defaultLimit = searchConfig.semantic().limit();
    }

    // ── Document indexing (unchanged) ─────────────────────────────────────────

    @Transactional
    public void indexDocument(Long documentId, String title, String description) {
        EmbeddingResponse embeddingResponse = embeddingService.embedDocument(title, description);

        DocumentEmbeddingEntity entity =
                embeddingRepo
                        .findByDocumentId(documentId)
                        .orElseGet(
                                () -> {
                                    DocumentEmbeddingEntity e = new DocumentEmbeddingEntity();
                                    e.setDocumentId(documentId);
                                    return e;
                                });

        entity.setEmbedding(embeddingResponse.getResult().getOutput());
        entity.setModel(embeddingResponse.getMetadata().getModel());
        entity.setUpdatedAt(OffsetDateTime.now());

        embeddingRepo.save(entity);
        log.debug("Indexed document id={} title='{}'", documentId, title);
    }

    @Transactional
    public void deleteIndex(Long documentId) {
        embeddingRepo.deleteByDocumentId(documentId);
        log.debug("Removed embedding for document id={}", documentId);
    }

    // ── Reindex all (documents + attachments) ─────────────────────────────────

    /**
     * Re-embeds every document and every attachment using batched API calls.
     *
     * @return total number of items processed
     */
    @Transactional
    public int reindexAll() {
        int docCount = reindexDocuments();
        int attCount = reindexAttachments();
        log.info("Full reindex complete: {} documents + {} attachments", docCount, attCount);
        return docCount + attCount;
    }

    private int reindexDocuments() {
        List<DocumentEntity> all = new ArrayList<>();
        documentRepo.findAll().forEach(all::add);

        int total = all.size();
        int countSuccess = 0;

        for (int offset = 0; offset < total; offset += reindexBatchSize) {
            List<DocumentEntity> slice =
                    all.subList(offset, Math.min(offset + reindexBatchSize, total));

            List<String> texts =
                    slice.stream()
                            .map(doc -> buildDocumentText(doc.getTitle(), doc.getDescription()))
                            .toList();

            List<float[]> vectors;
            try {
                vectors = embeddingService.embedBatch(texts);
            } catch (Exception ex) {
                log.error(
                        "Batch embedding failed for document slice offset={}: {}",
                        offset,
                        ex.getMessage(),
                        ex);
                continue;
            }

            for (int i = 0; i < slice.size(); i++) {
                DocumentEntity doc = slice.get(i);
                try {
                    upsertDocumentEmbedding(doc.getId(), vectors.get(i));
                    countSuccess++;
                } catch (Exception ex) {
                    log.error(
                            "Failed to persist embedding for document id={}: {}",
                            doc.getId(),
                            ex.getMessage(),
                            ex);
                }
            }
        }

        log.info("Document reindex: {}/{}", countSuccess, total);
        return total;
    }

    private int reindexAttachments() {
        List<AttachmentEntity> all = new ArrayList<>();
        attachmentRepo.findAll().forEach(all::add);

        int total = all.size();
        int countSuccess = 0;

        for (int offset = 0; offset < total; offset += reindexBatchSize) {
            List<AttachmentEntity> slice =
                    all.subList(offset, Math.min(offset + reindexBatchSize, total));

            List<String> texts = slice.stream().map(this::buildAttachmentText).toList();

            List<float[]> vectors;
            try {
                vectors = embeddingService.embedBatch(texts);
            } catch (Exception ex) {
                log.error(
                        "Batch embedding failed for attachment slice offset={}: {}",
                        offset,
                        ex.getMessage(),
                        ex);
                continue;
            }

            for (int i = 0; i < slice.size(); i++) {
                AttachmentEntity att = slice.get(i);
                try {
                    upsertAttachmentEmbedding(att.getId(), vectors.get(i));
                    countSuccess++;
                } catch (Exception ex) {
                    log.error(
                            "Failed to persist embedding for attachment id={}: {}",
                            att.getId(),
                            ex.getMessage(),
                            ex);
                }
            }
        }

        log.info("Attachment reindex: {}/{}", countSuccess, total);
        return total;
    }

    // ── Search (documents only — backward compat) ─────────────────────────────

    public List<SemanticSearchResult> search(String query, double threshold, int limit) {
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
            // Truncate to ~6000 chars to stay within token budget
            if (content.length() > 6000) content = content.substring(0, 6000);
            sb.append('\n').append(content);
        }
        return sb.toString();
    }

    private static String buildDocumentText(String title, String description) {
        StringBuilder sb = new StringBuilder(title == null ? "" : title.trim());
        if (description != null && !description.isBlank()) {
            sb.append('\n').append(description.trim());
        }
        return sb.toString();
    }
}
