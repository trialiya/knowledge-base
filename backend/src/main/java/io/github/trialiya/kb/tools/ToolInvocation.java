package io.github.trialiya.kb.tools;

import java.beans.Transient;
import java.util.Map;

public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        @Transient Map<String, ?> resultMeta,
        String resultGist) {

    @Transient
    public ToolInvocationMeta toMeta() {
        return new ToolInvocationMeta(name, arguments, status, error, resultMeta);
    }
}
