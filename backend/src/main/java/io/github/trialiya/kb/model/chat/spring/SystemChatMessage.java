package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import org.springframework.ai.chat.messages.SystemMessage;

public class SystemChatMessage extends SystemMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public SystemChatMessage(ChatMessageEntity chatMessageEntity) {
        super(chatMessageEntity.getText());
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
