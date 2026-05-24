package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentHistoryEntity;
import java.util.List;
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
    java.util.Optional<DocumentHistoryEntity> findByDocumentIdAndVersion(
            @Param("documentId") Long documentId, @Param("version") int version);
}
