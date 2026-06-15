package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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

    /**
     * Streams the direct children of a folder, ordered by position, fetched lazily one level at a
     * time. The caller <b>must</b> close the returned stream (try-with-resources) so the underlying
     * JDBC connection/cursor is released. Used by the subtree download to avoid loading the whole
     * {@code documents} table into memory.
     */
    Stream<DocumentEntity> findAllByParentIdOrderByPosition(Long parentId);

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

    /**
     * Recursively find all descendant IDs (used for cascade delete in Java).
     *
     * <p>The explicit CTE column list {@code descendants(id)} is required by H2 for recursive
     * queries and is valid standard SQL, so the same query runs on both PostgreSQL and H2.
     */
    @Query(
            """
        WITH RECURSIVE descendants(id) AS (
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
     * <p>The explicit CTE column list {@code ancestors(parent_id, depth)} is required by H2 for
     * recursive queries and is valid standard SQL, so the same query runs on both PostgreSQL and
     * H2.
     *
     * @param id the document id to find ancestors for
     * @return list of ancestor IDs, root first; empty for root-level nodes
     */
    @Query(
            """
    WITH RECURSIVE ancestors(parent_id, depth) AS (
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

    /**
     * Opens a slot at {@code fromPosition} in the given level by shifting every sibling at or after
     * it one step down. Unbounded on the right on purpose: position gaps are tolerated (ordering
     * relies only on relative position, never on contiguity), so there is no need to renumber the
     * whole level or close the hole left at the moved node's previous slot.
     *
     * <p>The moved node itself is excluded so the shift can't bump it while it still sits in the
     * same level. {@code IS NOT DISTINCT FROM} makes one query cover both a folder level and the
     * root level (parent_id IS NULL).
     */
    @Modifying
    @Query(
            """
        UPDATE documents
        SET position = position + 1
        WHERE parent_id IS NOT DISTINCT FROM :parentId
          AND position >= :fromPosition
          AND id <> :movedId
        """)
    void shiftPositionsFrom(
            @Param("parentId") Long parentId,
            @Param("fromPosition") int fromPosition,
            @Param("movedId") long movedId);

    /** Smallest position in a level (0 when the level is empty) — used to insert first. */
    @Query(
            "SELECT COALESCE(MIN(position), 0) FROM documents WHERE parent_id IS NOT DISTINCT FROM :parentId")
    int findMinPosition(@Param("parentId") Long parentId);

    /**
     * Windowed shift for moving a node UP within its own level: every sibling in {@code [newPos,
     * oldPos)} steps one down, and the {@code +1} window collapses exactly into the slot the moved
     * node vacates at {@code oldPos}. The moved node sits at {@code oldPos} and is outside the
     * half-open window by construction — no exclusion needed, no collisions, gaps tolerated (bounds
     * are real neighbour positions, not ordinal indexes).
     */
    @Modifying
    @Query(
            """
        UPDATE documents
        SET position = position + 1
        WHERE parent_id IS NOT DISTINCT FROM :parentId
          AND position >= :newPos
          AND position < :oldPos
        """)
    void shiftWindowUp(
            @Param("parentId") Long parentId,
            @Param("newPos") int newPos,
            @Param("oldPos") int oldPos);

    /**
     * Windowed shift for moving a node DOWN within its own level: every sibling in {@code (oldPos,
     * anchorPos]} steps one up — including the anchor itself, which ends at {@code anchorPos - 1},
     * right BEFORE the moved node taking {@code anchorPos}. The {@code -1} window collapses into
     * the vacated {@code oldPos} slot; the moved node is outside the window.
     */
    @Modifying
    @Query(
            """
        UPDATE documents
        SET position = position - 1
        WHERE parent_id IS NOT DISTINCT FROM :parentId
          AND position > :oldPos
          AND position <= :anchorPos
        """)
    void shiftWindowDown(
            @Param("parentId") Long parentId,
            @Param("oldPos") int oldPos,
            @Param("anchorPos") int anchorPos);
}
