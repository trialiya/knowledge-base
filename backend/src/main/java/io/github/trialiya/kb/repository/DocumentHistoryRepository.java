package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentHistoryEntity;
import io.github.trialiya.kb.model.doc.entity.DocumentHistoryShortResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface DocumentHistoryRepository extends CrudRepository<DocumentHistoryEntity, Long> {

    /**
     * Returns all history entries for a document, newest version first.
     *
     * @param documentId the document whose history to load
     */
    @Query(
            """
        SELECT * FROM document_history
        WHERE document_id = :documentId
        ORDER BY version DESC
        """)
    List<DocumentHistoryEntity> findByDocumentId(@Param("documentId") Long documentId);

    /**
     * Returns a single snapshot at the given version, or empty if none exists.
     *
     * @param documentId the document id
     * @param version the exact version to retrieve
     */
    @Query(
            """
        SELECT * FROM document_history
        WHERE document_id = :documentId
          AND version     = :version
        LIMIT 1
        """)
    Optional<DocumentHistoryEntity> findByDocumentIdAndVersion(
            @Param("documentId") Long documentId, @Param("version") int version);

    /**
     * История изменений описания: по одной строке на каждое distinct description_version (берём
     * самую раннюю — момент, когда контент стал таким), newest-first.
     */
    @Query(
            """
    SELECT id, document_id, version, title, type,
           updated_at, summary_source_version, description_version
    FROM (
             SELECT dh.*,
                    ROW_NUMBER() OVER (PARTITION BY description_version ORDER BY version) AS rn
             FROM document_history dh
             WHERE document_id = :documentId
         ) t
    WHERE rn = 1
    ORDER BY description_version DESC
    """)
    List<DocumentHistoryShortResult> findDescriptionHistory(@Param("documentId") long documentId);
}
