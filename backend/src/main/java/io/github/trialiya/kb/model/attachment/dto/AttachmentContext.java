package io.github.trialiya.kb.model.attachment.dto;

import io.github.trialiya.kb.tools.Compact;
import io.github.trialiya.kb.tools.ToolCallResponseItem;

public record AttachmentContext(long id, String fileName, String content)
        implements ToolCallResponseItem {
    @Override
    public String getFormattedResponse() {
        return Compact.tag("att:" + id)
                .add("file", fileName)
                .body(Compact.truncate(content, 50))
                .done();
    }
}
