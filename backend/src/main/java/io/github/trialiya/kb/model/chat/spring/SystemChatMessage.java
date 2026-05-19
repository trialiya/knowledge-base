package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import org.springframework.ai.chat.messages.SystemMessage;

public class SystemChatMessage extends SystemMessage implements IMessage {

    private final ChatMessage chatMessage;

    public SystemChatMessage(ChatMessage chatMessage) {
        super(chatMessage.getText());
        this.chatMessage = chatMessage;
    }

    public ChatMessage chatMessage() {
        return chatMessage;
    }
}
