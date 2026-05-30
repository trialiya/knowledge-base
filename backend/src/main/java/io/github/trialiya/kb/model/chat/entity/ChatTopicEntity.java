package io.github.trialiya.kb.model.chat.entity;

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
    private final boolean isUser; // соответствует IS_USER (true = пользователь, false = система)
    private final String topic;
    @CreatedDate private final LocalDateTime createdAt;
    @LastModifiedDate private final LocalDateTime updatedAt;
    @Transient private final boolean isNew;

    @PersistenceCreator
    public ChatTopicEntity(
            String conversationId,
            String user,
            boolean isUser,
            String topic,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(conversationId, user, isUser, topic, createdAt, updatedAt, false);
    }

    public ChatTopicEntity(
            String conversationId, String user, boolean isUser, String topic, boolean isNew) {
        this(conversationId, user, isUser, topic, null, null, isNew);
    }

    public ChatTopicEntity(
            String conversationId,
            String user,
            boolean isUser,
            String topic,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean isNew) {
        this.conversationId = conversationId;
        this.user = user;
        this.isUser = isUser;
        this.topic = topic;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isNew = isNew;
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
