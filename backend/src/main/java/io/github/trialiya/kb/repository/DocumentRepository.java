package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends CrudRepository<DocumentEntity, Long> {

    @Query("SELECT * FROM documents WHERE parent_id IS NULL ORDER BY type DESC, title")
    List<DocumentEntity> findRoots();

    @Query("SELECT * FROM documents WHERE parent_id = :parentId ORDER BY type DESC, title")
    List<DocumentEntity> findByParentId(@Param("parentId") Long parentId);

    /** Full-text search across title and content */
    @Query(
            """
        SELECT * FROM documents
        WHERE (title ILIKE '%' || :q || '%'
               OR DESCRIPTION ILIKE '%' || :q || '%')
        ORDER BY updated_at DESC
        LIMIT 20
        """)
    List<DocumentEntity> search(@Param("q") String q);

    /** Recursively find all descendant IDs (used for cascade delete in Java) */
    @Query(
            """
        WITH RECURSIVE descendants AS (
            SELECT id FROM documents WHERE id = :rootId
            UNION ALL
            SELECT d.id FROM documents d
            JOIN descendants anc ON d.parent_id = anc.id
        )
        SELECT id FROM descendants
        """)
    List<Long> findDescendantIds(@Param("rootId") Long rootId);
}
