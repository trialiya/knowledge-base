package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

public class AssistantChatMessage extends AssistantMessage implements IMessage {

    private final ChatMessage chatMessage;

    public AssistantChatMessage(ChatMessage chatMessage) {
        super(chatMessage.getText());
        this.chatMessage = chatMessage;
    }

    public ChatMessage chatMessage() {
        return chatMessage;
    }
}
