package io.github.trialiya.kb.model.tool;

import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;

public record ToolInvocationMeta(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        Map<String, ?> resultMeta) {}
