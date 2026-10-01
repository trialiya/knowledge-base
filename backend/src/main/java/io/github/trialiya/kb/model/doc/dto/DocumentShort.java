package io.github.trialiya.kb.model.doc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.model.tool.ToolJson;
import io.github.trialiya.kb.tools.Compact;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight document DTO returned by create / update / move operations.
 *
 * <p>The model reads neither {@code updatedAt} nor {@code summaryStale} (see {@link ToolJson}); a
 * {@code parentId} of a root-level document and a missing {@code summarySourceVersion} are left out.
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
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long parentId,
        int version,
        int descriptionVersion,
        @JsonView(ToolJson.UiOnly.class) LocalDateTime updatedAt,
        @JsonView(ToolJson.UiOnly.class) boolean summaryStale,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer summarySourceVersion)
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
