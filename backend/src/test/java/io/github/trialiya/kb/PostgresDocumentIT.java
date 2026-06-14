package io.github.trialiya.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.JdbcConfig;
import io.github.trialiya.kb.config.PgVectorJdbcConfig;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import io.github.trialiya.kb.repository.DocumentEmbeddingRepository;
import io.github.trialiya.kb.repository.DocumentHistoryRepository;
import io.github.trialiya.kb.repository.DocumentRepository;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.DocumentSummaryService;
import io.github.trialiya.kb.service.SemanticSearchService;
import io.github.trialiya.kb.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Интеграционные тесты основного функционала базы знаний на НАСТОЯЩЕМ PostgreSQL (pgvector),
 * поднятом через Testcontainers.
 *
 * <p>Здесь проверяется именно то, что нельзя проверить на H2:
 *
 * <ul>
 *   <li>применение продовых миграций {@code db/migration} целиком (vector, GiST/pg_trgm, identity,
 *       сидовые системные документы);
 *   <li>рекурсивные CTE ({@code findDescendantIds}, {@code findAncestorIds});
 *   <li>оконный сдвиг позиций при перемещении ({@code IS NOT DISTINCT FROM}) на реальном диалекте;
 *   <li>хранение и косинусный поиск эмбеддингов pgvector ({@code <=>}) — тип {@code vector} в H2
 *       просто отсутствует.
 * </ul>
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    CommonConfig.class,
    JdbcConfig.class,
    PgVectorJdbcConfig.class,
    DocumentEmbeddingRepository.class
})
class PostgresDocumentIT extends AbstractPostgresIntegrationTest {

    @Autowired private DocumentRepository repo;
    @Autowired private DocumentHistoryRepository historyRepo;
    @Autowired private DocumentEmbeddingRepository embeddingRepo;

    private DocumentService service() {
        return new DocumentService(
                repo,
                historyRepo,
                mock(DocumentSummaryService.class),
                mock(SemanticSearchService.class),
                null);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private DocumentEntity folder(String title, Long parentId, int position) {
        return save(title, "folder", parentId, position);
    }

    private DocumentEntity doc(String title, Long parentId, int position) {
        return save(title, "document", parentId, position);
    }

    private DocumentEntity save(String title, String type, Long parentId, int position) {
        return repo.save(
                new DocumentEntity(
                        null,
                        title,
                        type,
                        parentId,
                        null,
                        LocalDateTime.now(),
                        position,
                        false,
                        0,
                        null,
                        null,
                        1));
    }

    // ── Миграции и сид ───────────────────────────────────────────────────────

    @Test
    void productionMigrationsApplyAndSeedSystemDocuments() {
        // После всех миграций системный корень (id=1) — папка «Проект» (V2026.05.22
        // делает truncate + reinsert поверх сидов V1).
        DocumentEntity root = repo.findById(1L).orElseThrow();
        assertThat(root.getTitle()).isEqualTo("Проект");
        assertThat(root.getType()).isEqualTo("folder");
        assertThat(root.isSystem()).isTrue();

        // identity-генерация работает: новая вставка получает сгенерированный id,
        // не конфликтующий с сидовыми (1, 2).
        DocumentEntity fresh = doc("fresh", null, 999);
        assertThat(fresh.getId()).isNotNull().isNotIn(1L, 2L);
        assertThat(repo.findById(fresh.getId())).isPresent();
    }

    // ── Рекурсивные CTE на реальном PostgreSQL ───────────────────────────────

    @Test
    void recursiveDescendantAndAncestorQueries() {
        DocumentEntity top = folder("cte-top", null, 100);
        DocumentEntity mid = folder("cte-mid", top.getId(), 0);
        DocumentEntity leaf = doc("cte-leaf", mid.getId(), 0);

        assertThat(repo.findDescendantIds(top.getId()))
                .containsExactlyInAnyOrder(top.getId(), mid.getId(), leaf.getId());

        // от корня (исключая сам узел) вниз до непосредственного родителя
        assertThat(repo.findAncestorIds(leaf.getId())).containsExactly(top.getId(), mid.getId());
    }

    @Test
    void ilikeSearchMatchesTitle() {
        folder("Поиск-по-тексту уникальный-маркер", null, 101);

        List<DocumentEntity> found = repo.search("УникАльный-маркер");

        assertThat(found).extracting(DocumentEntity::getTitle).anyMatch(t -> t.contains("маркер"));
    }

    // ── Перемещение: оконный сдвиг (IS NOT DISTINCT FROM) ────────────────────

    @Test
    void moveReordersSiblingsOnRealPostgres() {
        DocumentEntity home = folder("move-home", null, 102);
        DocumentEntity a = doc("a", home.getId(), 0);
        DocumentEntity b = doc("b", home.getId(), 1);
        DocumentEntity c = doc("c", home.getId(), 2);
        DocumentEntity d = doc("d", home.getId(), 3);

        // [a b c d] → d сразу после a → [a d b c]
        Document moved = service().move(d.getId(), home.getId(), a.getId());
        assertThat(moved.parentId()).isEqualTo(home.getId());

        List<Long> order =
                repo.findByParentId(home.getId()).stream().map(DocumentEntity::getId).toList();
        assertThat(order).containsExactly(a.getId(), d.getId(), b.getId(), c.getId());
    }

    // ── pgvector: хранение и косинусный поиск ────────────────────────────────

    @Test
    void embeddingRoundTripsThroughVectorColumn() {
        DocumentEntity document = doc("vector-doc", null, 103);

        float[] vector = unitVector(1024, 0);
        embeddingRepo.save(
                new DocumentEmbeddingEntity(
                        null, document.getId(), vector, "bge-m3", OffsetDateTime.now()));

        float[] read =
                embeddingRepo.findByDocumentId(document.getId()).orElseThrow().getEmbedding();

        assertThat(read).hasSize(1024);
        assertThat(read[0]).isEqualTo(1.0f);
        assertThat(read[1]).isEqualTo(0.0f);
    }

    @Test
    void cosineSimilaritySearchRanksNearestFirst() {
        DocumentEntity near = doc("near", null, 104);
        DocumentEntity far = doc("far", null, 105);

        // near ≈ запрос (тот же базисный вектор), far — ортогонален.
        embeddingRepo.save(
                new DocumentEmbeddingEntity(
                        null, near.getId(), unitVector(1024, 0), "bge-m3", OffsetDateTime.now()));
        embeddingRepo.save(
                new DocumentEmbeddingEntity(
                        null, far.getId(), unitVector(1024, 1), "bge-m3", OffsetDateTime.now()));

        List<SemanticSearchResult> results =
                embeddingRepo.findSimilar(unitVector(1024, 0), 0.5, 10);

        assertThat(results).isNotEmpty();
        // первым должен идти «near» с similarity ≈ 1.0 (поле id хранит document_id)
        assertThat(results.getFirst().id()).isEqualTo(near.getId());
        assertThat(results.getFirst().similarity())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
        // ортогональный «far» (similarity ≈ 0) отсечён порогом 0.5
        assertThat(results).extracting(SemanticSearchResult::id).doesNotContain(far.getId());
    }

    /** Единичный вектор размерности {@code dim} с единицей в позиции {@code hot}. */
    private static float[] unitVector(int dim, int hot) {
        float[] v = new float[dim];
        v[hot] = 1.0f;
        return v;
    }
}
