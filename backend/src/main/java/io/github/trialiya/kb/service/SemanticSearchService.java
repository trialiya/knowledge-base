package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentEmbeddingRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the pgvector index for documents and exposes semantic search.
 *
 * <h3>Indexing</h3>
 *
 * <ul>
 *   <li>{@link #indexDocument} – upserts the embedding for one document (uses Postgres cache).
 *   <li>{@link #reindexAll} – full reindex using batch API calls for efficiency.
 *   <li>{@link #deleteIndex} – removes the embedding when a document is deleted.
 * </ul>
 *
 * <h3>Search</h3>
 *
 * {@link #search} embeds the query (cache-first) and runs a cosine similarity search.
 */
@Slf4j
@Service
public class SemanticSearchService {

    private static final double DEFAULT_THRESHOLD = 0.30;
    private static final int DEFAULT_LIMIT = 20;

    /**
     * How many documents are sent to {@link EmbeddingService#embedBatch} in one go during reindex.
     * Tune to stay within OpenAI's request-size limits (~2 048 items / ~300 k tokens per batch).
     * Configured via {@code kb.embedding.reindex-batch-size}.
     */
    private final int reindexBatchSize;

    private final EmbeddingService embeddingService;
    private final DocumentEmbeddingRepository embeddingRepo;
    private final DocumentRepository documentRepo;

    public SemanticSearchService(
            EmbeddingService embeddingService,
            DocumentEmbeddingRepository embeddingRepo,
            DocumentRepository documentRepo,
            EmbeddingConfiguration embeddingConfig) {
        this.embeddingService = embeddingService;
        this.embeddingRepo = embeddingRepo;
        this.documentRepo = documentRepo;
        this.reindexBatchSize = embeddingConfig.reindexBatchSize();
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

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

    /**
     * Re-embeds every document using batched API calls.
     *
     * <p>Documents are processed in slices of {@link #reindexBatchSize}. Within each slice, {@link
     * EmbeddingService#embedBatch} sends all cache-miss texts in a single HTTP request, so a full
     * reindex on a warm cache costs zero API calls.
     *
     * @return total number of documents processed (including failures)
     */
    @Transactional
    public int reindexAll() {
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
                        "Batch embedding failed for slice offset={}: {}",
                        offset,
                        ex.getMessage(),
                        ex);
                continue;
            }

            for (int i = 0; i < slice.size(); i++) {
                DocumentEntity doc = slice.get(i);
                try {
                    upsertEmbedding(doc.getId(), vectors.get(i));
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

        log.info("Reindex complete: {}/{} documents indexed", countSuccess, total);
        return total;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public List<SemanticSearchResult> search(String query, double threshold, int limit) {
        float[] queryVector = embeddingService.embed(query).getResult().getOutput();
        return embeddingRepo.findSimilar(queryVector, threshold, limit);
    }

    public List<SemanticSearchResult> search(String query) {
        return search(query, DEFAULT_THRESHOLD, DEFAULT_LIMIT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void upsertEmbedding(Long documentId, float[] vector) {
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

    private static String buildDocumentText(String title, String description) {
        StringBuilder sb = new StringBuilder(title == null ? "" : title.trim());
        if (description != null && !description.isBlank()) {
            sb.append('\n').append(description.trim());
        }
        return sb.toString();
    }
}
