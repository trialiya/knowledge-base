package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;
import java.time.LocalDateTime;

/**
 * Lightweight document DTO returned by create / update / move operations.
 *
 * @param summaryStale {@code true} when the description has changed since the last summarisation,
 *     i.e. the summary may no longer reflect the current content. Always {@code false} when {@link
 *     #summary} is {@code null} (nothing to be stale yet).
 * @param summarySourceVersion The {@code descriptionVersion} at which the summary was generated.
 *     {@code null} while {@link #summary} is {@code null}.
 */
public record DocumentShort(
        long id,
        String title,
        String type,
        String parentId,
        int version,
        LocalDateTime updatedAt,
        boolean summaryStale,
        Integer summarySourceVersion)
        implements ToolCallResponseItem {
    @Override
    public String getFormattedResponse() {
        return Compact.tag("doc:" + id)
                .add("title", title)
                .add("type", type)
                .add("parent", parentId)
                .add("version", version)
                .add("updated", updatedAt)
                .done();
    }
}
