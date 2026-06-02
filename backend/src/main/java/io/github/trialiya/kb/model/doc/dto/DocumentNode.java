package io.github.trialiya.kb.model.doc.dto;

import static java.util.stream.Collectors.joining;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full document node returned by {@code GET /api/documents/{id}} and tree/children endpoints.
 *
 * <p>Carries summary metadata alongside the document so the UI can show a "summary may be stale"
 * badge without an extra round-trip.
 *
 * @param system When true: the UI must hide delete/rename controls and the server will reject
 *     delete/rename requests with 403.
 * @param summary AI-generated summary, or {@code null} if never summarised.
 * @param summaryStale {@code true} when the description has changed since the last summarisation,
 *     i.e. the summary may no longer reflect the current content. Always {@code false} when {@link
 *     #summary} is {@code null} (nothing to be stale yet).
 * @param summarySourceVersion The {@code descriptionVersion} at which the summary was generated.
 *     {@code null} while {@link #summary} is {@code null}.
 */
public record DocumentNode(
        String id,
        String title,
        String type,
        String parentId,
        String description,
        LocalDateTime updatedAt,
        List<DocumentNode> children,
        boolean hasChildren,
        boolean system,
        String summary,
        boolean summaryStale,
        Integer summarySourceVersion)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        String kids =
                (children == null || children.isEmpty())
                        ? null
                        : "["
                                + children.stream()
                                        .map(c -> (c.id() + ":" + c.title()))
                                        .collect(joining(", "))
                                + "]";
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("type", type)
                .add("sys", system ? "1" : null)
                .add("parent", parentId)
                .add("children", kids)
                .body(Compact.truncate(description, 50))
                .done();
    }
}
