package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chat metadata as served to the UI.
 *
 * @param topic display title — the user's own title if the chat was renamed, otherwise the
 *     assistant-proposed one. Kept pre-resolved so callers that only render a title need no
 *     fallback logic.
 * @param aiTopic title proposed by the assistant ({@code recordChatInsights}), or null. Shown
 *     separately in the chat "Info" panel (alongside {@link #topic}) so a renamed chat can still
 *     display what the assistant would have called it. {@code userTopic} itself isn't exposed:
 *     nothing needs it once {@code topic} and {@code aiTopic} disagree, that already means the user
 *     renamed the chat.
 */
public record Chat(
        String conversationId,
        String user,
        String topic,
        String aiTopic,
        String model,
        String mode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ChatMessage> messages) {}
