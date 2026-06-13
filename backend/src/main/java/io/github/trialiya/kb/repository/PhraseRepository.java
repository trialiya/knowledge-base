package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.phrase.entity.PhraseEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface PhraseRepository extends ListCrudRepository<PhraseEntity, Long> {

    /** Public list (chat): enabled phrases only, grouped by category then position. */
    @Query("SELECT * FROM phrase WHERE enabled = true ORDER BY category, position, id")
    List<PhraseEntity> findEnabledOrdered();

    /** Admin list: every phrase including disabled ones. */
    @Query("SELECT * FROM phrase ORDER BY category, position, id")
    List<PhraseEntity> findAllOrdered();

    /**
     * Quick search by label (case-insensitive substring). Exact-label matches first, then shortest
     * labels, so the closest match bubbles up. {@code ILIKE} runs on both PostgreSQL and H2.
     */
    @Query(
            """
        SELECT * FROM phrase
        WHERE label ILIKE '%' || :q || '%'
        ORDER BY
            CASE WHEN LOWER(label) = LOWER(:q) THEN 0 ELSE 1 END,
            LENGTH(label),
            label
        LIMIT 20
        """)
    List<PhraseEntity> searchByLabel(@Param("q") String q);

    /** Largest position in a category (-1 when empty) — used to append a new phrase. */
    @Query("SELECT COALESCE(MAX(position), -1) FROM phrase WHERE category = :category")
    int findMaxPosition(@Param("category") String category);

    /**
     * Windowed shift for moving a phrase UP within its category: every sibling in {@code [newPos,
     * oldPos)} steps one down; the {@code +1} window collapses into the slot the moved phrase
     * vacates at {@code oldPos}. The moved phrase sits at {@code oldPos}, outside the half-open
     * window — no exclusion needed, gaps tolerated. Mirrors {@code
     * DocumentRepository.shiftWindowUp}.
     */
    @Modifying
    @Query(
            """
        UPDATE phrase
        SET position = position + 1
        WHERE category = :category
          AND position >= :newPos
          AND position < :oldPos
        """)
    void shiftWindowUp(
            @Param("category") String category,
            @Param("newPos") int newPos,
            @Param("oldPos") int oldPos);

    /**
     * Windowed shift for moving a phrase DOWN within its category: every sibling in {@code (oldPos,
     * anchorPos]} steps one up — including the anchor, which ends at {@code anchorPos - 1}, right
     * before the moved phrase taking {@code anchorPos}. The {@code -1} window collapses into the
     * vacated {@code oldPos} slot; the moved phrase is outside the window. Mirrors {@code
     * DocumentRepository.shiftWindowDown}.
     */
    @Modifying
    @Query(
            """
        UPDATE phrase
        SET position = position - 1
        WHERE category = :category
          AND position > :oldPos
          AND position <= :anchorPos
        """)
    void shiftWindowDown(
            @Param("category") String category,
            @Param("oldPos") int oldPos,
            @Param("anchorPos") int anchorPos);
}
