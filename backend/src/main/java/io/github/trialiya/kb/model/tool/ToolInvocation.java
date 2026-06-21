package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;

public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        @JsonIgnore Map<String, ?> resultMeta,
        String resultGist,
        @JsonIgnore String argumentsRaw,
        @JsonIgnore String resultText,
        int callIndex) {

    @JsonIgnore
    public ToolInvocationMeta toMeta(boolean hasDetails) {
        return new ToolInvocationMeta(name, arguments, status, error, resultMeta, hasDetails, callIndex);
    }
}
