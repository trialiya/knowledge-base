package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        @Nullable String error,
        @JsonIgnore @Nullable Map<String, ?> resultMeta,
        @Nullable String resultGist,
        @JsonIgnore String argumentsRaw,
        @JsonIgnore @Nullable String resultText,
        int callIndex) {

    @JsonIgnore
    public ToolInvocationMeta toMeta(boolean hasDetails) {
        return new ToolInvocationMeta(
                name, arguments, status, error, resultMeta, hasDetails, callIndex, resultGist);
    }
}
