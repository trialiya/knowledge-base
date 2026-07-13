package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

public record StreamMessage(String message, @Nullable String finishReason) {}
