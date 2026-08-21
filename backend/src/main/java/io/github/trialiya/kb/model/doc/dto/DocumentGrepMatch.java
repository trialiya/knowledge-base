package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One match of the {@code grepDocuments} AI tool — the knowledge-base twin of {@code GitGrepMatch},
 * down to the markup of {@code text}.
 *
 * <p>With context lines the text uses the {@code git grep -C} format: match lines are wrapped in
 * {@code :N:}, context lines in {@code -N-}. Line numbers count lines of the document's markdown
 * description.
 *
 * @param documentId id of the document the match was found in
 * @param title document title, so a hit is readable without a {@code getDocument} call just for the
 *     name
 * @param sectionPath section the match fell into, addressed as {@code getDocumentOutline} does —
 *     the direct route to a pinpoint edit ({@code getDocumentSection} / {@code
 *     updateDocumentSection}). {@code null} when the description has no sections at all
 * @param matchLine line number of the match (1-based); of the first match when a block holds
 *     several
 * @param text the block: a single line without context, a multi-line fragment with it
 */
public record DocumentGrepMatch(
        long documentId, String title, @Nullable String sectionPath, int matchLine, String text)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        return "doc:"
                + documentId
                + (sectionPath == null ? "" : " > " + sectionPath)
                + ":"
                + matchLine;
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentId", documentId);
        meta.put("title", title);
        if (sectionPath != null) {
            meta.put("sectionPath", sectionPath);
        }
        meta.put("matchLine", matchLine);
        return meta;
    }
}
