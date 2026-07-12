package io.github.trialiya.kb.model.tool;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

public record ToolCallDetail(
        int callIndex,
        String name,
        @Nullable String argumentsRaw,
        String status,
        @Nullable String error,
        @Nullable String resultText,
        @Nullable Object resultMeta,
        LocalDateTime createdAt) {}
