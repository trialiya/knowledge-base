package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;

public record ChatMessage(String content, String type, LocalDateTime timestamp) {}
