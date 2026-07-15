package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentType;
import io.github.trialiya.kb.model.embedding.EmbeddingEntityType;
import io.github.trialiya.kb.repository.AttachmentEmbeddingRepository;
import io.github.trialiya.kb.repository.AttachmentRepository;
import io.github.trialiya.kb.repository.DocumentEmbeddingRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import io.github.trialiya.kb.repository.EmbeddingCacheRepository;
import io.github.trialiya.kb.repository.EmbeddingTaskRepository;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end test of the embedding pipeline on a real PostgreSQL/pgvector database: {@link
 * EmbeddingService} (cache + chunking), {@link EmbeddingExecutor} (concurrency), {@link
 * EmbeddingTaskScheduler} (claim/dispatch/retry) and {@link EmbeddingCacheCleanupTask} (eviction),
 * wired together the way {@code kb.search.semantic.enabled=true} wires them in production. Only the
 * AI HTTP call itself is stubbed — a mocked {@link OpenAiEmbeddingModel} that turns each input text
 * into a deterministic one-hot vector, so caching, batching, chunking/mean-pooling and retry
 * bookkeeping can all be asserted precisely without a real embedding model.
 *
 * <p>{@link #schedulerIndexesDocumentEndToEndAndCachesTheEmbedding}, {@link
 * #schedulerRetriesTransientFailureThenSucceeds} and {@link
 * #schedulerMarksTaskFailedAfterMaxAttempts} exercise {@link EmbeddingTaskScheduler#poll} which
 * dispatches to {@link EmbeddingExecutor}'s virtual threads — a different DB connection than the
 * test's own. They run with the class's default test transaction suspended ({@code NOT_SUPPORTED}),
 * otherwise the worker thread's connection can never see rows the test thread just inserted (and
 * vice versa) before the surrounding transaction commits — so each such test cleans up its own rows
 * explicitly instead of relying on rollback.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    CommonConfig.class,
    JdbcConfig.class,
    PgVectorJdbcConfig.class,
    DocumentEmbeddingRepository.class,
    AttachmentEmbeddingRepository.class,
    EmbeddingTaskRepository.class
})
class EmbeddingPipelineIT extends AbstractPostgresIntegrationTest {

    private static final String MODEL_NAME = "bge-m3-test";

    @Autowired private DocumentRepository documentRepo;
    @Autowired private AttachmentRepository attachmentRepo;
    @Autowired private DocumentEmbeddingRepository docEmbeddingRepo;
    @Autowired private AttachmentEmbeddingRepository attEmbeddingRepo;
    @Autowired private EmbeddingCacheRepository cacheRepo;
    @Autowired private EmbeddingTaskRepository taskRepo;
    @Autowired private JdbcTemplate jdbc;

    // ── EmbeddingService: cache + batching (real Postgres cache, mocked AI call) ──────

    @Test
    void embedCachesRepeatedTextAndSkipsSecondApiCall() {
        OpenAiEmbeddingModel model = deterministicModel();
        EmbeddingService service =
                new EmbeddingService(
                        searchConfig(), model, cacheRepo, embeddingConfig(2, 3, 512, 64));
        String text = "повторяющийся текст для проверки кэша";

        float[] first = service.embed(text).getResult().getOutput();
        float[] second = service.embed(text).getResult().getOutput();

        assertThat(second).isEqualTo(first);
        verify(model, times(1)).call(any(EmbeddingRequest.class));
        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(text), MODEL_NAME))
                .isPresent();
    }

    @Test
    void embedBatchSendsOneApiCallForAllCacheMissesThenServesFromCache() {
        OpenAiEmbeddingModel model = deterministicModel();
        EmbeddingService service =
                new EmbeddingService(
                        searchConfig(), model, cacheRepo, embeddingConfig(2, 3, 512, 64));
        List<String> texts = List.of("batch-текст-один", "batch-текст-два", "batch-текст-три");

        List<float[]> vectors = service.embedBatch(texts);

        assertThat(vectors).hasSize(3);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(vectors.get(i)).isEqualTo(oneHot(texts.get(i)));
        }
        verify(model, times(1)).call(any(EmbeddingRequest.class));

        service.embedBatch(texts); // all cache hits now
        verify(model, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedChunksLongTextAndCachesEachChunkSeparately() {
        OpenAiEmbeddingModel model = deterministicModel();
        // maxTokens=5 -> 20 chars/chunk: forces the two paragraphs below into separate chunks.
        EmbeddingService service =
                new EmbeddingService(searchConfig(), model, cacheRepo, embeddingConfig(2, 3, 5, 1));
        String chunkOne = "chunk one text";
        String chunkTwo = "chunk two text";
        String longText = chunkOne + "\n\n" + chunkTwo;

        float[] pooled = service.embed(longText).getResult().getOutput();

        float[] expected = new float[1024];
        float[] v1 = oneHot(chunkOne);
        float[] v2 = oneHot(chunkTwo);
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (v1[i] + v2[i]) / 2f;
        }
        assertThat(pooled).isEqualTo(expected);
        verify(model, times(1)).call(any(EmbeddingRequest.class)); // both chunks in one batch call

        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(chunkOne), MODEL_NAME))
                .isPresent();
        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(chunkTwo), MODEL_NAME))
                .isPresent();
        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(longText), MODEL_NAME))
                .isEmpty(); // only chunks are cached, never the whole document text
    }

    // ── EmbeddingExecutor: concurrency cap (no DB involved) ───────────────────────────

    @Test
    void executorRejectsSubmitBeyondConfiguredWorkersThenAcceptsAgainAfterRelease()
            throws InterruptedException {
        EmbeddingExecutor executor = new EmbeddingExecutor(embeddingConfig(1, 3, 512, 64));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        boolean firstAccepted =
                executor.submit(
                        () -> {
                            taskStarted.countDown();
                            awaitUninterruptibly(releaseTask);
                        });
        assertThat(firstAccepted).isTrue();
        assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.availablePermits()).isEqualTo(0);
        assertThat(executor.submit(() -> {})).isFalse(); // no free permit — worker still busy

        releaseTask.countDown();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(executor.availablePermits()).isEqualTo(1));
        assertThat(executor.submit(() -> {})).isTrue(); // permit released, accepted again
    }

    // ── EmbeddingTaskScheduler: claim -> dispatch -> persist, end to end ──────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void schedulerIndexesDocumentEndToEndAndCachesTheEmbedding() {
        DocumentEntity doc =
                saveDoc("pipeline-happy-doc", "Съешь ещё этих мягких французских булок");
        taskRepo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, doc.getId());

        OpenAiEmbeddingModel model = deterministicModel();
        EmbeddingConfiguration config = embeddingConfig(2, 3, 512, 64);
        EmbeddingService embeddingService =
                new EmbeddingService(searchConfig(), model, cacheRepo, config);
        EmbeddingTaskScheduler scheduler = scheduler(embeddingService, config);

        try {
            scheduler.poll();

            // The worker persists the embedding first and marks the task done last, so awaiting
            // "done" guarantees the embedding is already committed and visible.
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(taskStatus(doc.getId())).isEqualTo("done"));

            var stored = docEmbeddingRepo.findByDocumentId(doc.getId()).orElseThrow();
            assertThat(stored.getModel()).isEqualTo(MODEL_NAME);
            assertThat(stored.getEmbedding()).hasSize(1024);

            // the document's title+description text is now cached from indexing above
            verify(model, times(1)).call(any(EmbeddingRequest.class));
            embeddingService.embedDocument(doc.getTitle(), doc.getDescription());
            verify(model, times(1)).call(any(EmbeddingRequest.class)); // still 1 -> cache hit
        } finally {
            cleanupDocument(doc.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void schedulerRetriesTransientFailureThenSucceeds() {
        DocumentEntity doc = saveDoc("pipeline-retry-doc", "первая попытка падает, вторая — нет");
        taskRepo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, doc.getId());

        AtomicInteger callCount = new AtomicInteger();
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class)))
                .thenAnswer(
                        inv -> {
                            if (callCount.getAndIncrement() == 0) {
                                throw new RuntimeException("transient AI outage");
                            }
                            return respondDeterministically(inv);
                        });

        EmbeddingConfiguration config = embeddingConfig(2, 3, 512, 64); // maxAttempts=3
        EmbeddingService embeddingService =
                new EmbeddingService(searchConfig(), model, cacheRepo, config);
        EmbeddingTaskScheduler scheduler = scheduler(embeddingService, config);

        try {
            scheduler.poll(); // attempt 1/3 fails -> back to pending
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(taskStatus(doc.getId())).isEqualTo("pending"));

            scheduler.poll(); // attempt 2/3 succeeds
            // Await "done" (the worker's last write), not the embedding row, so the status
            // assertion cannot race the worker between persisting the vector and markDone().
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(taskStatus(doc.getId())).isEqualTo("done"));

            assertThat(docEmbeddingRepo.findByDocumentId(doc.getId())).isPresent();
            assertThat(callCount.get()).isEqualTo(2);
        } finally {
            cleanupDocument(doc.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void schedulerMarksTaskFailedAfterMaxAttemptsAndPersistsNothing() {
        DocumentEntity doc = saveDoc("pipeline-permafail-doc", "всегда падает");
        taskRepo.enqueueIfAbsent(EmbeddingEntityType.DOCUMENT, doc.getId());

        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class))).thenThrow(new RuntimeException("AI down"));

        EmbeddingConfiguration config = embeddingConfig(2, 2, 512, 64); // maxAttempts=2
        EmbeddingService embeddingService =
                new EmbeddingService(searchConfig(), model, cacheRepo, config);
        EmbeddingTaskScheduler scheduler = scheduler(embeddingService, config);

        try {
            scheduler.poll(); // attempt 1/2 fails -> back to pending
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(taskStatus(doc.getId())).isEqualTo("pending"));

            scheduler.poll(); // attempt 2/2 fails -> permanently failed
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(taskStatus(doc.getId())).isEqualTo("failed"));

            assertThat(docEmbeddingRepo.findByDocumentId(doc.getId())).isEmpty();
        } finally {
            cleanupDocument(doc.getId());
        }
    }

    // ── EmbeddingCacheCleanupTask: LRU eviction on real Postgres ──────────────────────

    @Test
    void cleanupEvictsOnlyEntriesStaleBeyondTtl() {
        OpenAiEmbeddingModel model = deterministicModel();
        EmbeddingConfiguration config = embeddingConfig(2, 3, 512, 64); // ttlDays=30
        EmbeddingService service = new EmbeddingService(searchConfig(), model, cacheRepo, config);
        String freshText = "cache-cleanup-fresh-entry";
        String staleText = "cache-cleanup-stale-entry";

        service.embed(freshText);
        service.embed(staleText);
        jdbc.update(
                "UPDATE embedding_cache SET last_used_at = NOW() - INTERVAL '60 days' WHERE text_hash = ?",
                EmbeddingService.sha256(staleText));

        new EmbeddingCacheCleanupTask(cacheRepo, config).evictStaleEntries();

        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(freshText), MODEL_NAME))
                .isPresent();
        assertThat(cacheRepo.findByTextHashAndModel(EmbeddingService.sha256(staleText), MODEL_NAME))
                .isEmpty();
    }

    // ── Fixtures & helpers ─────────────────────────────────────────────────────────────

    private SearchConfiguration searchConfig() {
        return new SearchConfiguration(
                new SearchConfiguration.KeywordConfig(20),
                new SearchConfiguration.SemanticConfig(true, 0.3, 20),
                new SearchConfiguration.HybridConfig(0.4, 0.6, 0.2, 20));
    }

    private EmbeddingConfiguration embeddingConfig(
            int workers, int maxAttempts, int chunkerMaxTokens, int chunkerOverlapTokens) {
        return new EmbeddingConfiguration(
                MODEL_NAME,
                50,
                workers,
                20,
                maxAttempts,
                0, // retryBackoffSeconds=0 -> a reset task is immediately reclaimable
                10,
                7,
                new EmbeddingConfiguration.EmbeddingCacheConfiguration(true, 30, "0 0 2 * * *"),
                new EmbeddingConfiguration.EmbeddingChunkerConfiguration(
                        chunkerMaxTokens, chunkerOverlapTokens));
    }

    private EmbeddingTaskScheduler scheduler(
            EmbeddingService embeddingService, EmbeddingConfiguration config) {
        SemanticSearchService searchService =
                new SemanticSearchService(
                        embeddingService,
                        taskRepo,
                        docEmbeddingRepo,
                        documentRepo,
                        attEmbeddingRepo,
                        attachmentRepo,
                        searchConfig());
        EmbeddingExecutor executor = new EmbeddingExecutor(config);
        return new EmbeddingTaskScheduler(taskRepo, executor, searchService, config);
    }

    private DocumentEntity saveDoc(String title, String description) {
        return documentRepo.save(
                new DocumentEntity(
                        null,
                        title,
                        DocumentType.DOCUMENT,
                        null,
                        description,
                        LocalDateTime.now(),
                        0,
                        false,
                        0,
                        null,
                        null,
                        1));
    }

    private String taskStatus(Long documentId) {
        return jdbc.queryForObject(
                "SELECT status FROM embedding_tasks WHERE entity_type = 'document' AND entity_id = ?",
                String.class,
                documentId);
    }

    /** Undoes what the {@code NOT_SUPPORTED} tests committed outside the rollback safety net. */
    private void cleanupDocument(Long documentId) {
        docEmbeddingRepo.deleteByDocumentId(documentId);
        jdbc.update(
                "DELETE FROM embedding_tasks WHERE entity_type = 'document' AND entity_id = ?",
                documentId);
        documentRepo.deleteById(documentId);
        // Successful indexing also commits embedding_cache rows; the Postgres container is
        // shared JVM-wide, so drop everything this class's model name wrote.
        jdbc.update("DELETE FROM embedding_cache WHERE model = ?", MODEL_NAME);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Mocked AI call: turns each input text into a deterministic one-hot vector. */
    private static OpenAiEmbeddingModel deterministicModel() {
        OpenAiEmbeddingModel model = mock(OpenAiEmbeddingModel.class);
        when(model.call(any(EmbeddingRequest.class)))
                .thenAnswer(EmbeddingPipelineIT::respondDeterministically);
        return model;
    }

    private static EmbeddingResponse respondDeterministically(
            org.mockito.invocation.InvocationOnMock inv) {
        EmbeddingRequest request = inv.getArgument(0);
        List<Embedding> embeddings =
                IntStream.range(0, request.getInstructions().size())
                        .mapToObj(i -> new Embedding(oneHot(request.getInstructions().get(i)), i))
                        .toList();
        return new EmbeddingResponse(embeddings);
    }

    private static float[] oneHot(String text) {
        float[] v = new float[1024];
        v[Math.floorMod(text.hashCode(), 1024)] = 1.0f;
        return v;
    }
}
