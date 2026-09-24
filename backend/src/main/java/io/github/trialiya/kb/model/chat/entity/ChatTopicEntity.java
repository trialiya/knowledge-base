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
    @Nullable private final Integer aiTopicTurn;
    @Nullable private final String model;
    @Nullable private final String mode;
    @Nullable private final String project;
    @CreatedDate private final LocalDateTime createdAt;
    @LastModifiedDate private final LocalDateTime updatedAt;
    @Transient private final boolean isNew;

    /** Канонический конструктор. */
    public ChatTopicEntity(
            String conversationId,
            String user,
            @Nullable String userTopic,
            @Nullable String aiTopic,
            @Nullable Integer aiTopicTurn,
            @Nullable String model,
            @Nullable String mode,
            @Nullable String project,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean isNew) {
        this.conversationId = conversationId;
        this.user = user;
        this.userTopic = userTopic;
        this.aiTopic = aiTopic;
        this.aiTopicTurn = aiTopicTurn;
        this.model = model;
        this.mode = mode;
        this.project = project;
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
            @Nullable Integer aiTopicTurn,
            @Nullable String model,
            @Nullable String mode,
            @Nullable String project,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(
                conversationId,
                user,
                userTopic,
                aiTopic,
                aiTopicTurn,
                model,
                mode,
                project,
                createdAt,
                updatedAt,
                false);
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
                null,
                model,
                null,
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

    /** Название чата, предложенное ИИ (см. {@code AiTopicService}). */
    @Nullable
    public String getAiTopic() {
        return aiTopic;
    }

    /**
     * На каком по счёту ходе разговора {@code AiTopicService} последний раз назвал чат; {@code
     * null} — не называл, или название придумано до того, как номер стали записывать.
     */
    @Nullable
    public Integer getAiTopicTurn() {
        return aiTopicTurn;
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

    /** Проект (репозиторий), выбранный в чате; {@code null} — дефолтный из {@code kb.projects}. */
    @Nullable
    public String getProject() {
        return project;
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
