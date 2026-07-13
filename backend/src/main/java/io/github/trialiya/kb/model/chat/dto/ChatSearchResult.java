package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/** Один чат в результатах поиска по названию и/или содержимому сообщений. */
public record ChatSearchResult(
        String conversationId,
        String topic,
        LocalDateTime updatedAt,
        boolean titleMatched,
        int messageMatchCount,
        @Nullable String snippet) {}
