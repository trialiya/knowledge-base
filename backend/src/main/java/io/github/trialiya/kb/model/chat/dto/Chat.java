package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chat metadata as served to the UI.
 *
 * @param topic display title — {@code userTopic} if the user renamed the chat, otherwise {@code
 *     aiTopic}. Kept as its own field so callers that only render a title need no fallback logic.
 * @param userTopic title set explicitly by the user (PUT {@code .../topic}), or null
 * @param aiTopic title proposed by the assistant ({@code recordChatInsights}), or null. Shown
 *     separately in the chat "Info" panel, where the two are meant to be told apart.
 */
public record Chat(
        String conversationId,
        String user,
        String topic,
        String userTopic,
        String aiTopic,
        String model,
        String mode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ChatMessage> messages) {}
