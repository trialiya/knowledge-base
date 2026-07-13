package io.github.trialiya.kb.model.chat.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record MessagePage(
        List<ChatMessage> messages, boolean hasMore, @Nullable MessageCursor oldestCursor) {}
