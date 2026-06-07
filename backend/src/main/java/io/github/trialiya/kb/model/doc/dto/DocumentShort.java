package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
        int descriptionVersion,
        LocalDateTime updatedAt,
        boolean summaryStale,
        Integer summarySourceVersion)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {
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

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("type", type);
        meta.put("title", title);
        meta.put("parent", parentId);
        meta.put("version", version);
        meta.put("descriptionVersion", descriptionVersion);
        meta.put("updated", updatedAt);
        return meta;
    }
}
