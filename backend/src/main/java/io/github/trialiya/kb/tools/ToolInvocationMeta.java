package io.github.trialiya.kb.tools;

import java.util.Map;

public record ToolInvocationMeta(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        Map<String, ?> resultMeta) {}
