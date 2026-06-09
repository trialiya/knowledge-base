package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

public record Chat(
        String conversationId,
        String user,
        String topic,
        String model,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ChatMessage> messages) {}
