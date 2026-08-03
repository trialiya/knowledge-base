package io.github.trialiya.kb.model.chat.entity;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
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
    @Nullable private final String userTopic;
    @Nullable private final String aiTopic;
    @Nullable private final String model;
    @Nullable private final String mode;
    @CreatedDate private final LocalDateTime createdAt;
    @LastModifiedDate private final LocalDateTime updatedAt;
    @Transient private final boolean isNew;

    /** Канонический конструктор. */
    public ChatTopicEntity(
            String conversationId,
            String user,
            @Nullable String userTopic,
            @Nullable String aiTopic,
            @Nullable String model,
            @Nullable String mode,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean isNew) {
        this.conversationId = conversationId;
        this.user = user;
        this.userTopic = userTopic;
        this.aiTopic = aiTopic;
        this.model = model;
        this.mode = mode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isNew = isNew;
    }

    /** Гидрация строки из БД. */
    @PersistenceCreator
    public ChatTopicEntity(
            String conversationId,
            String user,
            @Nullable String userTopic,
            @Nullable String aiTopic,
            @Nullable String model,
            @Nullable String mode,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(conversationId, user, userTopic, aiTopic, model, mode, createdAt, updatedAt, false);
    }

    public ChatTopicEntity(
            String conversationId,
            String user,
            @Nullable String userTopic,
            @Nullable String aiTopic,
            @Nullable String model,
            boolean isNew) {
        // createdAt/updatedAt are placeholders: @CreatedDate/@LastModifiedDate auditing
        // overwrites them before the row is actually inserted.
        this(
                conversationId,
                user,
                userTopic,
                aiTopic,
                model,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                isNew);
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUser() {
        return user;
    }

    /** Название чата, заданное пользователем вручную (см. {@code PUT .../topic}). */
    @Nullable
    public String getUserTopic() {
        return userTopic;
    }

    /** Название чата, предложенное ИИ (см. {@code recordChatInsights}). */
    @Nullable
    public String getAiTopic() {
        return aiTopic;
    }

    /** Название для отображения: пользовательское имеет приоритет над предложенным ИИ. */
    @Nullable
    public String getDisplayTopic() {
        return userTopic != null ? userTopic : aiTopic;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public String getMode() {
        return mode;
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
