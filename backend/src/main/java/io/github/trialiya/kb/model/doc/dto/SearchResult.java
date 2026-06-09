package io.github.trialiya.kb.model.doc.dto;

import static java.util.stream.Collectors.joining;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record SearchResult(
        Long id,
        String title,
        String snippet,
        LocalDateTime updatedAt,
        String summary,
        List<Parent> parentList)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    public record Parent(long id, String title) {}

    @Override
    public String getFormattedResponse() {
        String parents =
                parentList == null
                        ? null
                        : parentList.stream().map(SearchResult.Parent::title).collect(joining("/"));
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("in", parents)
                .add("upd", updatedAt.toLocalDate())
                .body(Compact.truncate(snippet, 50))
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "id", id,
                "title", title);
    }
}
