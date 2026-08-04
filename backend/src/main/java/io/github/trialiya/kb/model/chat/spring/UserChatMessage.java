package io.github.trialiya.kb.model.chat.spring;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import org.springframework.ai.chat.messages.UserMessage;

public class UserChatMessage extends UserMessage implements IMessage {

    private final ChatMessageEntity chatMessageEntity;

    public UserChatMessage(ChatMessageEntity chatMessageEntity) {
        this(chatMessageEntity, chatMessageEntity.getText());
    }

    /**
     * Вариант с текстом, отличным от сохранённого: к вопросу дописан блок приложенного контекста
     * (см. {@code ContextItemService.render}). Блок собирается при каждом чтении истории и в БД не
     * попадает — {@link #chatMessage()} по-прежнему отдаёт исходную строку, так что и дедупликация
     * в {@code ChatMemoryService.saveAll}, и показ пользователю работают с тем, что он написал.
     */
    public UserChatMessage(ChatMessageEntity chatMessageEntity, String renderedText) {
        super(renderedText);
        this.chatMessageEntity = chatMessageEntity;
    }

    public ChatMessageEntity chatMessage() {
        return chatMessageEntity;
    }
}
