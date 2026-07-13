package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ChatMessage(
        @Nullable Long id,
        String content,
        String type,
        LocalDateTime timestamp,
        List<ToolInvocationMeta> toolInvocationMetas,
        @Nullable String runId,
        boolean toolCalls) {}
