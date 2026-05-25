package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository
        extends CrudRepository<DocumentEntity, Long>,
                PagingAndSortingRepository<DocumentEntity, Long>,
                DocumentRepositoryCustom {

    /** Root-level nodes ordered by their explicit position. */
    @Query("SELECT * FROM documents WHERE parent_id IS NULL ORDER BY position, type DESC, title")
    List<DocumentEntity> findRoots();

    /** Children of a given parent ordered by their explicit position. */
    @Query(
            "SELECT * FROM documents WHERE parent_id = :parentId ORDER BY position, type DESC, title")
    List<DocumentEntity> findByParentId(@Param("parentId") Long parentId);

    /** Paginated children of a given parent folder, sorted by position. */
    Page<DocumentEntity> findByParentId(Long parentId, Pageable pageable);

    /** Paginated root-level nodes (parentId IS NULL), sorted by position. */
    Page<DocumentEntity> findByParentIdIsNull(Pageable pageable);

    /** Full-text search across title and description. */
    @Query(
            """
        SELECT * FROM documents
        WHERE (title ILIKE '%' || :q || '%'
               OR summary ILIKE '%' || :q || '%'
               OR description ILIKE '%' || :q || '%')
        ORDER BY updated_at DESC
        LIMIT 20
        """)
    List<DocumentEntity> search(@Param("q") String q);

    /** Recursively find all descendant IDs (used for cascade delete in Java). */
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

    /** Update the position of a single document. */
    @Modifying
    @Query("UPDATE documents SET position = :position WHERE id = :id")
    void updatePosition(@Param("id") Long id, @Param("position") int position);

    /** Check whether a node has any children (used for lazy-load skeleton). */
    @Query("SELECT COUNT(*) > 0 FROM documents WHERE parent_id = :parentId")
    boolean hasChildren(@Param("parentId") Long parentId);

    /**
     * Search by title only (case-insensitive substring match). Returns exact-title matches first,
     * then partial matches, ordered by title length so the closest match bubbles up.
     */
    @Query(
            """
        SELECT * FROM documents
        WHERE title ILIKE '%' || :name || '%'
        ORDER BY
            CASE WHEN LOWER(title) = LOWER(:name) THEN 0 ELSE 1 END,
            LENGTH(title),
            title
        LIMIT 20
        """)
    List<DocumentEntity> findByTitleContaining(@Param("name") String name);

    /**
     * Returns the set of document IDs that have at least one child. Replaces per-row {@code
     * hasChildren(id)} calls in getTreeSkeleton.
     */
    @Query("SELECT DISTINCT parent_id FROM documents WHERE parent_id IS NOT NULL")
    Set<Long> findAllParentIds();

    /**
     * Returns ancestor IDs from root down to (but not including) the given node, using a single
     * recursive CTE query. The result is ordered from root to the immediate parent.
     *
     * @param id the document id to find ancestors for
     * @return list of ancestor IDs, root first; empty for root-level nodes
     */
    @Query(
            """
    WITH RECURSIVE ancestors AS (
        SELECT parent_id, 1 AS depth
        FROM documents
        WHERE id = :id AND parent_id IS NOT NULL
        UNION ALL
        SELECT d.parent_id, a.depth + 1
        FROM documents d
        JOIN ancestors a ON d.id = a.parent_id
        WHERE d.parent_id IS NOT NULL
    )
    SELECT parent_id FROM ancestors ORDER BY depth DESC
    """)
    List<Long> findAncestorIds(@Param("id") Long id);

    /**
     * Returns IDs of system-protected nodes from the given list. Used by reorder to skip system
     * nodes in a single query.
     */
    @Query("SELECT id FROM documents WHERE id IN (:ids) AND is_system = true")
    Set<Long> findSystemIdsByIdIn(@Param("ids") List<Long> ids);

    //     ── batchUpdatePositions comes from DocumentRepositoryCustom ──────────
    //    void batchUpdatePositions(Map<Long, Integer> positionMap);
}
