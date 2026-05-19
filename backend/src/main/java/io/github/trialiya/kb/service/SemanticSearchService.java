package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentEmbeddingRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
 *   <li>{@link #indexDocument} – upserts the embedding for one document.
 *   <li>{@link #reindexAll} – full reindex (call once on startup or via admin endpoint).
 *   <li>{@link #deleteIndex} – removes the embedding when a document is deleted.
 * </ul>
 *
 * <h3>Search</h3>
 *
 * {@link #search} embeds the query on the fly and runs a cosine similarity search against the
 * stored vectors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    /** Default cosine-similarity threshold – tune to taste. */
    private static final double DEFAULT_THRESHOLD = 0.30;

    /** Default maximum number of results returned. */
    private static final int DEFAULT_LIMIT = 20;

    private final EmbeddingService embeddingService;
    private final DocumentEmbeddingRepository embeddingRepo;
    private final DocumentRepository documentRepo;

    // ── Indexing ─────────────────────────────────────────────────────────────

    /**
     * Creates or updates the embedding for a single document. Call this whenever a document's title
     * or description changes.
     */
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

    /** Removes the stored embedding for a document (called on document deletion). */
    @Transactional
    public void deleteIndex(Long documentId) {
        embeddingRepo.deleteByDocumentId(documentId);
        log.debug("Removed embedding for document id={}", documentId);
    }

    /**
     * Re-embeds every document in the database. Useful for initial population or after changing the
     * embedding model.
     *
     * @return number of documents indexed
     */
    @Transactional
    public int reindexAll() {
        Iterable<DocumentEntity> all = documentRepo.findAll();

        int count = 0;
        int countSuccess = 0;
        for (var doc : all) {
            count++;
            try {
                indexDocument(doc.getId(), doc.getTitle(), doc.getDescription());
                countSuccess++;
            } catch (Exception ex) {
                log.error("Failed to index document id={}: {}", doc.getId(), ex.getMessage(), ex);
            }
        }
        log.info("Reindex complete: {}/{} documents indexed", countSuccess, count);
        return count;
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Embeds {@code query} and returns the most similar documents.
     *
     * @param query natural-language query
     * @param threshold minimum cosine similarity (0–1); results below are excluded
     * @param limit maximum number of results
     */
    public List<SemanticSearchResult> search(String query, double threshold, int limit) {
        float[] queryVector = embeddingService.embed(query).getResult().getOutput();
        return embeddingRepo.findSimilar(queryVector, threshold, limit);
    }

    /** Overload with sensible defaults. */
    public List<SemanticSearchResult> search(String query) {
        return search(query, DEFAULT_THRESHOLD, DEFAULT_LIMIT);
    }
}
