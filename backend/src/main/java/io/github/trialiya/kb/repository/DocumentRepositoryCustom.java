package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.dto.SearchResult;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Custom fragment for {@link DocumentRepository} operations that require dynamic SQL (e.g. batch
 * CASE expressions) and cannot be expressed as a static {@code @Query}.
 */
public interface DocumentRepositoryCustom {

    /**
     * Updates the {@code position} column for multiple documents in a single SQL statement.
     *
     * <p>Generates:
     *
     * <pre>
     * UPDATE documents SET position = CASE id
     *   WHEN 5 THEN 0
     *   WHEN 3 THEN 1
     *   WHEN 8 THEN 2
     * END
     * WHERE id IN (5, 3, 8)
     * </pre>
     *
     * @param positionMap map of document id → new position value; empty map is a no-op
     */
    void batchUpdatePositions(Map<Long, Integer> positionMap);

    /**
     * Batch-resolves ancestor breadcrumbs for many documents in a single recursive query.
     *
     * <p>For each requested document id the result holds its ancestors ordered from the root down
     * to the immediate parent; the document itself is <b>not</b> included. Root-level documents (no
     * parent) are simply absent from the map. Use this instead of calling {@link
     * DocumentRepository#findAncestorIds} once per row — it avoids the N+1 query problem when
     * building search results.
     *
     * @param ids document ids to resolve; {@code null}/empty input returns an empty map
     * @return map of document id → ordered ancestor list (root first, immediate parent last)
     */
    Map<Long, List<SearchResult.Parent>> findAncestorsByIds(Collection<Long> ids);
}
