package io.github.trialiya.kb.model.tool;

import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * @param resultText what the model was answered with
 * @param fullResultText the whole result when {@code resultText} is a trimmed view of it ({@link
 *     ModelView}); {@code null} when the model got the result whole — the detail view then offers
 *     no second version
 */
public record ToolCallDetail(
        String name,
        @Nullable String argumentsRaw,
        ToolInvocationStatus status,
        @Nullable String error,
        @Nullable String resultText,
        @Nullable String fullResultText,
        @Nullable Object resultMeta,
        LocalDateTime createdAt) {}
