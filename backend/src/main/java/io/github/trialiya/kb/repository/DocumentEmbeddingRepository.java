package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link DocumentEmbeddingEntity}.
 *
 * <p>Nearest-neighbour search uses a raw {@link JdbcTemplate} query because Spring Data JDBC does
 * not support the {@code <=>} (cosine distance) operator natively.
 */
@Repository
@RequiredArgsConstructor
public class DocumentEmbeddingRepository {

    private final EmbeddingCrudRepository crud;
    private final JdbcTemplate jdbc;

    // ── Delegate simple CRUD ─────────────────────────────────────────────────

    public Optional<DocumentEmbeddingEntity> findByDocumentId(Long documentId) {
        return crud.findByDocumentId(documentId);
    }

    public DocumentEmbeddingEntity save(DocumentEmbeddingEntity entity) {
        return crud.save(entity);
    }

    public void deleteByDocumentId(Long documentId) {
        crud.deleteByDocumentId(documentId);
    }

    // ── Semantic search ──────────────────────────────────────────────────────

    /**
     * Returns at most {@code limit} documents ordered by cosine similarity to {@code
     * queryEmbedding}. Only documents whose similarity exceeds {@code threshold} are returned (0 =
     * orthogonal, 1 = identical).
     *
     * <p>The query uses the {@code <=>} cosine-distance operator from pgvector; similarity = 1 −
     * distance.
     */
    public List<SemanticSearchResult> findSimilar(
            float[] queryEmbedding, double threshold, int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        // language=SQL
        String sql =
                """
                SELECT
                    d.id          AS document_id,
                    d.title,
                    d.description,
                    d.updated_at,
                    1 - (de.embedding <=> ?::vector) AS similarity
                FROM document_embeddings de
                JOIN documents d ON d.id = de.document_id
                WHERE 1 - (de.embedding <=> ?::vector) >= ?
                ORDER BY similarity DESC
                LIMIT ?
                """;

        return jdbc.query(
                sql,
                ps -> {
                    ps.setString(1, vectorLiteral);
                    ps.setString(2, vectorLiteral);
                    ps.setDouble(3, threshold);
                    ps.setInt(4, limit);
                },
                (rs, rowNum) ->
                        new SemanticSearchResult(
                                rs.getString("document_id"),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getTimestamp("updated_at") != null
                                        ? rs.getTimestamp("updated_at").toLocalDateTime()
                                        : null,
                                rs.getDouble("similarity")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public static String toVectorLiteral(float[] v) {
        return IntStream.range(0, v.length)
                .mapToObj(i -> Float.toString(v[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
