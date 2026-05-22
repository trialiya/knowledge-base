package io.github.trialiya.kb.repository;

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
}
