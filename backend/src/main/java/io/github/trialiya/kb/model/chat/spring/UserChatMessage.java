package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import org.springframework.ai.chat.messages.UserMessage;

public class UserChatMessage extends UserMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public UserChatMessage(ChatMessageEntity chatMessageEntity) {
        super(chatMessageEntity.getText());
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
