package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.List;
import java.util.Map;

/** Result of an outline attempt: the symbols plus which parser produced them. */
public record OutlineResult(String parser, List<GitSymbol> symbols)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        return parser + " " + symbols;
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of(
                "parser",
                parser,
                "symbols",
                symbols.stream().map(ToolCallResultMetaProvider::getResultMeta).toList());
    }
}
