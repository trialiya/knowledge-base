package io.github.trialiya.kb.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
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
    public void batchUpdatePositions(Map<Long, Integer> positionMap) {
        if (positionMap.isEmpty()) return;

        StringBuilder sql = new StringBuilder("UPDATE documents SET position = CASE id ");
        List<Object> params = new ArrayList<>(positionMap.size() * 3);

        for (var entry : positionMap.entrySet()) {
            sql.append("WHEN ? THEN ? ");
            params.add(entry.getKey());
            params.add(entry.getValue());
        }

        sql.append("END WHERE id IN (");
        sql.append(positionMap.keySet().stream().map(id -> "?").collect(Collectors.joining(",")));
        sql.append(')');
        params.addAll(positionMap.keySet());

        jdbcTemplate.update(sql.toString(), params.toArray());
    }
}
