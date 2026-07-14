package io.github.trialiya.kb.model.doc.dto;

import static java.util.stream.Collectors.joining;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown outline of a document returned by the {@code getDocumentOutline} AI tool: section
 * addresses and sizes without any content, so the model can decide which sections to fetch or
 * update instead of transferring the whole description.
 *
 * @param descriptionVersion current content version — must be passed back to {@code
 *     updateDocumentSection} so a concurrent edit is detected instead of silently splicing against
 *     stale section boundaries
 */
public record DocumentOutline(
        long id, String title, int descriptionVersion, List<OutlineSection> sections)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /**
     * @param path section address for {@code getDocumentSection} / {@code updateDocumentSection}
     * @param level heading level 1–6; 0 for the preamble pseudo-section
     * @param title heading text; empty for the preamble
     * @param chars size of the whole subtree in characters
     * @param subsections number of direct child headings
     */
    public record OutlineSection(
            String path, int level, String title, int chars, int subsections) {}

    @Override
    public String getFormattedResponse() {
        String body =
                sections.stream()
                        .map(s -> (s.level() > 0 ? "H" + s.level() + " " : "") + s.path())
                        .collect(joining(", "));
        return Compact.tag("outline:" + id)
                .add("title", title)
                .add("descVer", descriptionVersion)
                .add("sections", sections.size())
                .body(Compact.truncate(body, 200))
                .done();
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("title", title);
        meta.put("descriptionVersion", descriptionVersion);
        meta.put("sections", sections.size());
        return meta;
    }
}
