package io.github.trialiya.kb.model.tool;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;

public record ToolCallDetail(
        int callIndex,
        String name,
        @Nullable String argumentsRaw,
        String status,
        @Nullable String error,
        @Nullable String resultText,
        @Nullable Object resultMeta,
        LocalDateTime createdAt) {}
