package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of a working-tree file mutation ({@code createFile} / {@code editFile}).
 *
 * <p>{@link #getResultMeta()} feeds the frontend "file changes" block under the AI answer (see
 * {@code FileChangeBlock.jsx}): path, operation and line counters are always present, {@code diff}
 * (unified diff of this particular edit, already truncated server-side) only for edits.
 *
 * @param operation {@code "create"} or {@code "edit"}
 * @param path file path relative to repo root
 * @param additions lines added by this operation
 * @param deletions lines removed by this operation
 * @param lineCount total lines in the file after the operation
 * @param diff unified diff of this operation; null for created files
 */
public record GitEditResult(
        String operation,
        String path,
        int additions,
        int deletions,
        int lineCount,
        @Nullable String diff)
        implements ToolCallResultMetaProvider, ToolCallResponseItem {

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", path);
        meta.put("operation", operation);
        meta.put("additions", additions);
        meta.put("deletions", deletions);
        meta.put("lineCount", lineCount);
        if (diff != null) {
            meta.put("diff", diff);
        }
        return meta;
    }

    @Override
    public String getFormattedResponse() {
        return operation + " " + path + " (+" + additions + "/-" + deletions + ")";
    }
}
