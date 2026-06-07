package io.github.trialiya.kb.model.tool;

import java.beans.Transient;

public interface ToolCallResponseItem {

    @Transient
    String getFormattedResponse();
}
