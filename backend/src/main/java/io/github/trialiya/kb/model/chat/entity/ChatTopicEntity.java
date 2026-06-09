package io.github.trialiya.kb.model.chat.entity;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "chat_topic")
public class ChatTopicEntity implements Persistable<String> {

    @Id private final String conversationId;
    private final String user;
    private final boolean isUser;
    private final String topic;
    @Nullable private final String model;
    @CreatedDate private final LocalDateTime createdAt;
    @LastModifiedDate private final LocalDateTime updatedAt;
    @Transient private final boolean isNew;

    /** Канонический конструктор. */
    public ChatTopicEntity(
            String conversationId,
            String user,
            boolean isUser,
            String topic,
            @Nullable String model,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean isNew) {
        this.conversationId = conversationId;
        this.user = user;
        this.isUser = isUser;
        this.topic = topic;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isNew = isNew;
    }

    /** Гидрация строки из БД. */
    @PersistenceCreator
    public ChatTopicEntity(
            String conversationId,
            String user,
            boolean isUser,
            String topic,
            @Nullable String model,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(conversationId, user, isUser, topic, model, createdAt, updatedAt, false);
    }

    public ChatTopicEntity(
            String conversationId,
            String user,
            boolean isUser,
            String topic,
            @Nullable String model,
            boolean isNew) {
        this(conversationId, user, isUser, topic, model, null, null, isNew);
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUser() {
        return user;
    }

    public boolean isUser() {
        return isUser;
    }

    public String getTopic() {
        return topic;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public String getId() {
        return getConversationId();
    }
}
