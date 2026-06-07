package io.github.trialiya.kb.model.tool;

import java.beans.Transient;
import java.util.Map;

public interface ToolCallResultMetaProvider {

    @Transient
    Map<String, Object> getResultMeta();
}
