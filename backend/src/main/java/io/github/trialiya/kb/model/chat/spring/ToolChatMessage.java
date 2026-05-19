package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.ToolResponseMessage;

public class ToolChatMessage extends ToolResponseMessage implements IMessage {

    private final ChatMessage chatMessage;

    public ToolChatMessage(ChatMessage chatMessage) {
        super(List.of(), Map.of());
        this.chatMessage = chatMessage;
    }

    public ChatMessage chatMessage() {
        return chatMessage;
    }
}
