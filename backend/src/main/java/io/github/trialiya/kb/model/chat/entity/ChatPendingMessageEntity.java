package io.github.trialiya.kb.model.chat.entity;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Сообщение, отправленное в чат во время активного прогона и ждущее доставки в {@code
 * chat_message}. Почему отдельная таблица, а не ряд истории с флагом, и как работает доставка — см.
 * шапку миграции {@code V2026.08.27_00__create_chat_pending_message.sql} и {@code
 * PendingMessageService}, единственного писателя и читателя этой таблицы.
 *
 * <p>{@code meta} несёт только {@code contextItems} вопроса — тем же конвертером, что и {@code
 * chat_message.meta}, поэтому вложения переезжают в доставленный ряд без переупаковки. {@code
 * model}/{@code mode}/{@code project} — снимок выбора на момент отправки: follow-up прогон (когда
 * доставка случилась уже после завершения текущего) обязан поехать на нём.
 */
@Table(name = "chat_pending_message")
public class ChatPendingMessageEntity implements Persistable<Long> {

    @Id private long id;
    @NonNull private final String conversationId;
    @NonNull private final String user;
    @NonNull private final String content;
    @Nullable private final String clientMsgId;
    @Nullable private final ChatMessageMeta meta;
    @Nullable private final String model;
    @Nullable private final String mode;
    @Nullable private final String project;
    @NonNull private final LocalDateTime createdAt;

    public ChatPendingMessageEntity(
            long id,
            @NonNull String conversationId,
            @NonNull String user,
            @NonNull String content,
            @Nullable String clientMsgId,
            @Nullable ChatMessageMeta meta,
            @Nullable String model,
            @Nullable String mode,
            @Nullable String project,
            @NonNull LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.user = user;
        this.content = content;
        this.clientMsgId = clientMsgId;
        this.meta = meta;
        this.model = model;
        this.mode = mode;
        this.project = project;
        this.createdAt = createdAt;
    }

    @Override
    @NonNull
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return id == 0L;
    }

    @NonNull
    public String getConversationId() {
        return conversationId;
    }

    @NonNull
    public String getUser() {
        return user;
    }

    @NonNull
    public String getContent() {
        return content;
    }

    @Nullable
    public String getClientMsgId() {
        return clientMsgId;
    }

    @Nullable
    public ChatMessageMeta getMeta() {
        return meta;
    }

    /** Приложенное к сообщению; пустой список, если меты нет. */
    public List<ContextItem> getContextItems() {
        return meta != null ? meta.contextItems() : List.of();
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public String getMode() {
        return mode;
    }

    @Nullable
    public String getProject() {
        return project;
    }

    @NonNull
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
