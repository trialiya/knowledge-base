package io.github.trialiya.kb.model.doc.dto;

import static java.util.stream.Collectors.joining;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;
import java.time.LocalDateTime;
import java.util.List;

public record SearchResult(
        String id,
        String title,
        String snippet,
        LocalDateTime updatedAt,
        String summary,
        List<Parent> parentList)
        implements ToolCallResponseItem {

    public record Parent(String id, String title) {}

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
}
