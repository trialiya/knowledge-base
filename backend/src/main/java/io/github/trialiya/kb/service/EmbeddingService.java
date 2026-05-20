package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.model.cache.EmbeddingCacheEntity;
import io.github.trialiya.kb.repository.EmbeddingCacheRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces {@code float[]} embedding vectors with three layers of optimisation:
 *
 * <ol>
 *   <li><b>Postgres cache</b> — vectors are stored by SHA-256(text)+model; a repeated text never
 *       hits the OpenAI API twice. {@code last_used_at} is bumped on every hit so stale rows can be
 *       evicted by {@link EmbeddingCacheCleanupTask}.
 *   <li><b>Batch requests</b> — {@link #embedBatch} sends all cache-miss texts in a single HTTP
 *       call, which is more efficient than N serial calls.
 *   <li><b>Chunking</b> — texts longer than {@value TextChunker#DEFAULT_MAX_TOKENS} tokens are
 *       split by {@link TextChunker} and each chunk is embedded separately; the resulting vectors
 *       are averaged (mean pooling) to produce one document-level vector.
 * </ol>
 *
 * <p>Configure in {@code application.yaml}:
 *
 * <pre>
 * kb:
 *   embedding:
 *     model: text-embedding-3-small   # must match spring.ai.openai.embedding.options.model
 *     cache:
 *       enabled: true
 * </pre>
 */
@Slf4j
@Service
public class EmbeddingService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final EmbeddingCacheRepository cacheRepo;
    private final TextChunker chunker;
    private final boolean cacheEnabled;

    /**
     * The embedding model name, read from {@code kb.embedding.model}. Must match the value used
     * when a vector was originally stored, otherwise cache lookups miss.
     */
    private final String modelName;

    public EmbeddingService(
            OpenAiEmbeddingModel embeddingModel,
            EmbeddingCacheRepository cacheRepo,
            EmbeddingConfiguration embeddingConfig) {
        this.embeddingModel = embeddingModel;
        this.cacheRepo = cacheRepo;
        this.cacheEnabled = embeddingConfig.cache().enabled();
        this.modelName = embeddingConfig.model();
        this.chunker =
                TextChunker.builder()
                        .maxTokens(embeddingConfig.chunker().maxTokens())
                        .overlapTokens(embeddingConfig.chunker().overlapTokens())
                        .build();
    }

    public String getModelName() {
        return modelName;
    }

    // ── Single embed (used by SemanticSearchService for query vectors) ────────

    /**
     * Embeds a single text, going through the cache and chunker. For query strings this is
     * typically called with short text, so chunking rarely activates.
     */
    @Transactional
    public EmbeddingResponse embed(String text) {
        float[] vector = embedWithCacheAndChunking(text);
        return wrapVector(vector, text);
    }

    /**
     * Embeds {@code title + "\n" + description} as a document representation. Delegates to {@link
     * #embed} so caching and chunking apply automatically.
     */
    @Transactional
    public EmbeddingResponse embedDocument(String title, String description) {
        String combined = buildDocumentText(title, description);
        return embed(combined);
    }

    // ── Batch embed (used by SemanticSearchService.reindexAll) ───────────────

    /**
     * Embeds a list of texts efficiently.
     *
     * <ul>
     *   <li>Cache hits are served from Postgres without any API call.
     *   <li>All cache misses whose text fits in one chunk are sent in a <em>single</em> batch
     *       request to OpenAI.
     *   <li>Long texts that require chunking are embedded individually (each still goes through the
     *       chunk-level cache).
     * </ul>
     *
     * @param texts input strings, in order
     * @return vectors in the same order as {@code texts}
     */
    @Transactional
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        float[][] results = new float[texts.size()][];

        // ── 1. Serve cache hits, collect misses ───────────────────────────────
        List<Integer> missIndexes = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (!chunker.fitsInOneChunk(text)) {
                missIndexes.add(i);
                missTexts.add(null); // placeholder — long text, handled below
                continue;
            }
            Optional<float[]> cached = lookupCache(text, modelName);
            if (cached.isPresent()) {
                results[i] = cached.get();
                log.debug("Cache hit for text hash={}", sha256(text));
            } else {
                missIndexes.add(i);
                missTexts.add(text);
            }
        }

        // ── 2. Batch-call for short cache-miss texts ──────────────────────────
        List<String> shortMisses = new ArrayList<>();
        List<Integer> shortMissOriginalIndexes = new ArrayList<>();

        for (int j = 0; j < missIndexes.size(); j++) {
            if (missTexts.get(j) != null) {
                shortMisses.add(missTexts.get(j));
                shortMissOriginalIndexes.add(missIndexes.get(j));
            }
        }

        if (!shortMisses.isEmpty()) {
            log.debug("Batch-embedding {} cache-miss texts", shortMisses.size());
            EmbeddingResponse batchResp = callApi(shortMisses);
            List<Embedding> embeddings = batchResp.getResults();

            for (int k = 0; k < shortMisses.size(); k++) {
                float[] vec = embeddings.get(k).getOutput();
                int originalIndex = shortMissOriginalIndexes.get(k);
                results[originalIndex] = vec;
                writeCache(shortMisses.get(k), modelName, vec);
            }
        }

        // ── 3. Long texts: chunk → embed → average ────────────────────────────
        for (int j = 0; j < missIndexes.size(); j++) {
            if (missTexts.get(j) == null) {
                int originalIndex = missIndexes.get(j);
                results[originalIndex] = embedWithCacheAndChunking(texts.get(originalIndex));
            }
        }

        return Arrays.asList(results);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private float[] embedWithCacheAndChunking(String text) {
        if (chunker.fitsInOneChunk(text)) {
            return lookupCache(text, modelName)
                    .orElseGet(
                            () -> {
                                float[] vec =
                                        callApi(List.of(text)).getResults().get(0).getOutput();
                                writeCache(text, modelName, vec);
                                return vec;
                            });
        }

        List<String> chunks = chunker.split(text);
        log.debug("Text split into {} chunks for embedding", chunks.size());

        List<float[]> chunkVectors = new ArrayList<>(chunks.size());
        List<String> missChunks = new ArrayList<>();

        for (String chunk : chunks) {
            Optional<float[]> cached = lookupCache(chunk, modelName);
            if (cached.isPresent()) {
                chunkVectors.add(cached.get());
            } else {
                missChunks.add(chunk);
            }
        }

        if (!missChunks.isEmpty()) {
            EmbeddingResponse resp = callApi(missChunks);
            for (int i = 0; i < missChunks.size(); i++) {
                float[] vec = resp.getResults().get(i).getOutput();
                chunkVectors.add(vec);
                writeCache(missChunks.get(i), modelName, vec);
            }
        }

        return meanPool(chunkVectors);
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private Optional<float[]> lookupCache(String text, String model) {
        if (!cacheEnabled) return Optional.empty();
        String hash = sha256(text);
        return cacheRepo
                .findByTextHashAndModel(hash, model)
                .map(
                        entity -> {
                            cacheRepo.touchLastUsed(hash, model, OffsetDateTime.now());
                            return entity.getEmbedding();
                        });
    }

    private void writeCache(String text, String model, float[] vector) {
        if (!cacheEnabled) return;
        String hash = sha256(text);
        if (cacheRepo.findByTextHashAndModel(hash, model).isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now();
            EmbeddingCacheEntity entity =
                    new EmbeddingCacheEntity(null, hash, model, vector, now, now);
            cacheRepo.save(entity);
            log.debug("Cache write: hash={} model={}", hash, model);
        }
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private EmbeddingResponse callApi(List<String> texts) {
        log.info("Calling embedding api: {} items", texts.size());
        return embeddingModel.call(
                new EmbeddingRequest(texts, OpenAiEmbeddingOptions.builder().build()));
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String buildDocumentText(String title, String description) {
        StringBuilder sb = new StringBuilder(title == null ? "" : title.trim());
        if (description != null && !description.isBlank()) {
            sb.append('\n').append(description.trim());
        }
        return sb.toString();
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static float[] meanPool(List<float[]> vectors) {
        if (vectors.isEmpty()) throw new IllegalArgumentException("No vectors to pool");
        if (vectors.size() == 1) return vectors.get(0);

        int dim = vectors.get(0).length;
        float[] sum = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) sum[i] += v[i];
        }
        float n = vectors.size();
        for (int i = 0; i < dim; i++) sum[i] /= n;
        return sum;
    }

    private static EmbeddingResponse wrapVector(float[] vector, String inputText) {
        Embedding embedding = new Embedding(vector, 0);
        return new EmbeddingResponse(List.of(embedding));
    }
}
