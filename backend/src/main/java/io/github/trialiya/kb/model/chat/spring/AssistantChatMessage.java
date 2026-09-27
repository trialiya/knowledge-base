package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.advisor.ReasoningAdvisor;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;

public class AssistantChatMessage extends AssistantMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public AssistantChatMessage(ChatMessageEntity chatMessageEntity, boolean replayReasoning) {
        // tool_calls восстанавливаем из tool_data — без них модель не примет
        // последующее TOOL-сообщение (пара assistant.tool_calls ↔ tool.tool_call_id).
        super(
                chatMessageEntity.getText(),
                reasoningMetadata(chatMessageEntity, replayReasoning),
                chatMessageEntity.getToolData() != null
                        ? chatMessageEntity.getToolData().toToolCalls()
                        : List.of(),
                List.of());
        this.chatMessageEntity = chatMessageEntity;
    }

    /**
     * Рассуждение уходит модели ключом {@link ReasoningAdvisor#REASONING_CONTENT}: по нему {@code
     * OpenAiChatModel} выписывает {@code reasoning_content} в сообщение запроса.
     */
    private static Map<String, Object> reasoningMetadata(
            ChatMessageEntity entity, boolean replayReasoning) {
        final String reasoning = entity.getReasoning();
        return replayReasoning && reasoning != null && !reasoning.isEmpty()
                ? Map.of(ReasoningAdvisor.REASONING_CONTENT, reasoning)
                : Map.of();
    }

    @Override
    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
