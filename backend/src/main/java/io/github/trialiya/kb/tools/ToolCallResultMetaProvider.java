package io.github.trialiya.kb.tools;

import java.beans.Transient;
import java.util.Map;

public interface ToolCallResultMetaProvider {

    @Transient
    Map<String, Object> getResultMeta();
}
