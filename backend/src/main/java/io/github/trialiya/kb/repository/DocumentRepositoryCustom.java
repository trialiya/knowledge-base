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
