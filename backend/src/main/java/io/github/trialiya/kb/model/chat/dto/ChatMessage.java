package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.time.LocalDateTime;
import java.util.List;

public record ChatMessage(
        String content,
        String type,
        LocalDateTime timestamp,
        List<ToolInvocationMeta> toolInvocationMetas) {}
