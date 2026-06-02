package io.github.trialiya.kb.tools;

import java.util.Map;

public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        String resultGist) {}
