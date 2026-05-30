package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.ToolResponseMessage;

public class ToolChatMessage extends ToolResponseMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public ToolChatMessage(ChatMessageEntity chatMessageEntity) {
        super(List.of(), Map.of());
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
