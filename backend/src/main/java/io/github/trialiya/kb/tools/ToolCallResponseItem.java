package io.github.trialiya.kb.tools;

import java.beans.Transient;

public interface ToolCallResponseItem {

    @Transient
    String getFormattedResponse();
}
