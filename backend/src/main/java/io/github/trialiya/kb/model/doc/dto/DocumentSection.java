package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.HashMap;
import java.util.Map;

/**
 * One markdown section of a document returned by the {@code getDocumentSection} AI tool: the
 * heading with its body and subsections, without the rest of the document.
 *
 * @param descriptionVersion current content version — must be passed back to {@code
 *     updateDocumentSection} so a concurrent edit is detected
 * @param content raw markdown of the whole subtree, heading line included
 */
public record DocumentSection(long id, String path, int descriptionVersion, String content)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        return Compact.tag("section:" + id)
                .add("path", path)
                .add("descVer", descriptionVersion)
                .body(Compact.truncate(content, 50))
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("path", path);
        meta.put("descriptionVersion", descriptionVersion);
        return meta;
    }
}
