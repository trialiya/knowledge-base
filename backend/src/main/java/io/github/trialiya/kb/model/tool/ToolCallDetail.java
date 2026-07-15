package io.github.trialiya.kb.model.tool;

import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

public record ToolCallDetail(
        int callIndex,
        String name,
        @Nullable String argumentsRaw,
        ToolInvocationStatus status,
        @Nullable String error,
        @Nullable String resultText,
        @Nullable Object resultMeta,
        LocalDateTime createdAt) {}
