package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import org.springframework.ai.chat.messages.AssistantMessage;

public class AssistantChatMessage extends AssistantMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public AssistantChatMessage(ChatMessageEntity chatMessageEntity) {
        super(chatMessageEntity.getText());
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
