package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;

/** Нагрузка события {@link ChatEventType#USER_MESSAGE}. */
public record UserMessagePayload(String text, LocalDateTime createdAt) {}
