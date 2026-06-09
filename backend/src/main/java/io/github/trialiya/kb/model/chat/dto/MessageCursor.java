package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;

public record MessageCursor(LocalDateTime createdAt, long id) {}
