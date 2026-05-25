package io.github.trialiya.kb.repository;

import static io.github.trialiya.kb.repository.DocumentEmbeddingRepository.toVectorLiteral;

import io.github.trialiya.kb.model.attachment.entity.AttachmentEmbeddingEntity;
import io.github.trialiya.kb.model.search.SemanticSearchResult;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link AttachmentEmbeddingEntity}.
 *
 * <p>Mirrors {@link DocumentEmbeddingRepository} but joins against {@code attachments} instead of
 * {@code documents}. The cosine-distance query uses the same pgvector {@code <=>} operator.
 */
@Repository
@RequiredArgsConstructor
public class AttachmentEmbeddingRepository {

    private final AttachmentEmbeddingCrudRepository crud;
    private final JdbcTemplate jdbc;

    // ── Delegate CRUD ────────────────────────────────────────────────────────

    public Optional<AttachmentEmbeddingEntity> findByAttachmentId(Long attachmentId) {
        return crud.findByAttachmentId(attachmentId);
    }

    public AttachmentEmbeddingEntity save(AttachmentEmbeddingEntity entity) {
        return crud.save(entity);
    }

    public void deleteByAttachmentId(Long attachmentId) {
        crud.deleteByAttachmentId(attachmentId);
    }

    // ── Semantic search ──────────────────────────────────────────────────────

    /**
     * Returns at most {@code limit} attachments ordered by cosine similarity.
     *
     * <p>Results are mapped to {@link SemanticSearchResult} (reusing the same record) where:
     *
     * <ul>
     *   <li>{@code id} = attachment id (stringified)
     *   <li>{@code title} = file_name
     *   <li>{@code description} = summary (AI-generated) or first N chars of content
     * </ul>
     */
    public List<SemanticSearchResult> findSimilar(
            float[] queryEmbedding, double threshold, int limit) {
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        // language=SQL
        String sql =
                """
                SELECT
                    a.id                                       AS attachment_id,
                    a.file_name                                AS title,
                    COALESCE(a.summary, LEFT(a.content, 300))  AS description,
                    a.updated_at,
                    a.summary,
                    1 - (ae.embedding <=> ?::vector)           AS similarity
                FROM attachment_embeddings ae
                JOIN attachments a ON a.id = ae.attachment_id
                WHERE 1 - (ae.embedding <=> ?::vector) >= ?
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
                                rs.getString("attachment_id"),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getTimestamp("updated_at") != null
                                        ? rs.getTimestamp("updated_at").toLocalDateTime()
                                        : null,
                                rs.getString("summary"),
                                rs.getDouble("similarity")));
    }
}
