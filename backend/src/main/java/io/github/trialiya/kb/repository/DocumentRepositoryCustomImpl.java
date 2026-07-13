package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.dto.SearchResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-based implementation of {@link DocumentRepositoryCustom}.
 *
 * <p>The class name must be {@code DocumentRepositoryCustomImpl} (or end with {@code Impl}) so
 * Spring Data picks it up automatically as the fragment backing {@link DocumentRepository}.
 */
@AllArgsConstructor
public class DocumentRepositoryCustomImpl implements DocumentRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<Long, List<SearchResult.Parent>> findAncestorsByIds(@Nullable Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();

        // One placeholder per id for the IN (...) list.
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));

        // Recursive CTE: seed with each requested id, then walk up via parent_id.
        // `depth` grows toward the root, so ORDER BY depth DESC yields root-first
        // (the same ordering as DocumentRepository.findAncestorIds, just batched
        // and carrying the title). The seed row itself is never emitted — only
        // its ancestors. The explicit column list on `chain(...)` is required by
        // H2 for recursive CTEs and is valid standard SQL, so the query runs on
        // both PostgreSQL and H2.
        String sql =
                """
                WITH RECURSIVE chain(seed_id, ancestor_id, depth) AS (
                    SELECT d.id AS seed_id, d.parent_id AS ancestor_id, 1 AS depth
                    FROM documents d
                    WHERE d.id IN (%s) AND d.parent_id IS NOT NULL
                    UNION ALL
                    SELECT c.seed_id, p.parent_id, c.depth + 1
                    FROM chain c
                    JOIN documents p ON p.id = c.ancestor_id
                    WHERE p.parent_id IS NOT NULL
                )
                SELECT c.seed_id, c.ancestor_id, a.title
                FROM chain c
                JOIN documents a ON a.id = c.ancestor_id
                ORDER BY c.seed_id, c.depth DESC
                """
                        .formatted(placeholders);

        Map<Long, List<SearchResult.Parent>> result = new HashMap<>();
        jdbcTemplate.query(
                sql,
                rs -> {
                    long seedId = rs.getLong("seed_id");
                    result.computeIfAbsent(seedId, k -> new ArrayList<>())
                            .add(
                                    new SearchResult.Parent(
                                            rs.getLong("ancestor_id"), rs.getString("title")));
                },
                ids.toArray());
        return result;
    }
}
