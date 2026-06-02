package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.tools.ToolCallResponseItem;
import java.util.List;

/** Result of an outline attempt: the symbols plus which parser produced them. */
public record OutlineResult(String parser, List<GitSymbol> symbols)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        return parser + " " + symbols;
    }
}
