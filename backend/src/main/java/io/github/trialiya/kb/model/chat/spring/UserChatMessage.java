package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import org.springframework.ai.chat.messages.UserMessage;

public class UserChatMessage extends UserMessage implements IMessage {

    private final ChatMessage chatMessage;

    public UserChatMessage(ChatMessage chatMessage) {
        super(chatMessage.getText());
        this.chatMessage = chatMessage;
    }

    public ChatMessage chatMessage() {
        return chatMessage;
    }
}
