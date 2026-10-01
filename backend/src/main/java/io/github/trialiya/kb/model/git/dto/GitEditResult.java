package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ModelView;
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
 * <p>The model is answered without {@code diff} ({@link #forModel}): the patch repeats the edit the
 * model has just written itself, up to 500 lines of it. The whole result is kept for the call's
 * detail view, and the file-changes block reads the diff from the meta.
 *
 * @param diff unified diff of this operation; null for created files
 */
public record GitEditResult(
        String operation,
        String path,
        int additions,
        int deletions,
        int lineCount,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String diff)
        implements ToolCallResultMetaProvider, ToolCallResponseItem, ModelView {

    @Override
    public ForModel forModel() {
        return new ForModel(operation, path, additions, deletions, lineCount);
    }

    /**
     * What the model reads: the result without {@code diff}. Public and named as {@link #forModel}'s
     * return type so the native image registers it with the tool's signature (see {@code
     * NativeHints}).
     */
    public record ForModel(String operation, String path, int additions, int deletions, int lineCount) {}

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
