package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;

public class AssistantChatMessage extends AssistantMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public AssistantChatMessage(ChatMessageEntity chatMessageEntity) {
        // tool_calls восстанавливаем из tool_data — без них модель не примет
        // последующее TOOL-сообщение (пара assistant.tool_calls ↔ tool.tool_call_id).
        super(
                chatMessageEntity.getText(),
                Map.of(),
                chatMessageEntity.getToolData() != null
                        ? chatMessageEntity.getToolData().toToolCalls()
                        : List.of(),
                List.of());
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
