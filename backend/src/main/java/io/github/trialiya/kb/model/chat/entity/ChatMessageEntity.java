package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.chat.spring.AssistantChatMessage;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.chat.spring.SystemChatMessage;
import io.github.trialiya.kb.model.chat.spring.ToolChatMessage;
import io.github.trialiya.kb.model.chat.spring.UserChatMessage;
import jakarta.annotation.Nonnull;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "chat_message")
public class ChatMessageEntity implements Message, Persistable<Long> {

    @Id private long id;
    @Nonnull private String conversationId;
    @Nonnull private String content;
    @Nonnull private MessageType type;
    private long position;
    private boolean summarized;
    private boolean summary;
    @Nonnull private LocalDateTime createdAt;

    @PersistenceCreator
    public ChatMessageEntity(
            long id,
            @Nonnull String conversationId,
            @Nonnull String content,
            @Nonnull MessageType type,
            long position,
            boolean summarized,
            boolean summary,
            @Nonnull LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.content = content;
        this.type = type;
        this.position = position;
        this.summarized = summarized;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    @Override
    @Nonnull
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return id == 0L;
    }

    @Override
    public String getText() {
        return content;
    }

    @Override
    public MessageType getMessageType() {
        return type;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of();
    }

    @Nonnull
    public String getConversationId() {
        return conversationId;
    }

    @Nonnull
    public String getContent() {
        return content;
    }

    @Nonnull
    public MessageType getType() {
        return type;
    }

    public long getPosition() {
        return position;
    }

    public boolean isSummarized() {
        return summarized;
    }

    public boolean isSummary() {
        return summary;
    }

    @Nonnull
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public IMessage getMessage() {
        return switch (type) {
            case TOOL -> new ToolChatMessage(this);
            case USER -> new UserChatMessage(this);
            case SYSTEM -> new SystemChatMessage(this);
            case ASSISTANT -> new AssistantChatMessage(this);
        };
    }
}
