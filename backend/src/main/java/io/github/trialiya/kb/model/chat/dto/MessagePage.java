package io.github.trialiya.kb.model.chat.dto;

import java.util.List;

public record MessagePage(
        List<ChatMessage> messages, boolean hasMore, MessageCursor oldestCursor) {}
