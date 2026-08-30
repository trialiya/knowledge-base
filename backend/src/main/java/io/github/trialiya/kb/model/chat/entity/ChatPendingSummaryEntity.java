package io.github.trialiya.kb.model.chat.entity;

import java.time.LocalDateTime;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Написанная, но ещё не применённая сводка чата — одна из очереди: пока применения нет, разговор
 * идёт, и следующий раунд сжимает накопившееся за этой. Зачем такая пауза и почему сводок у чата
 * бывает несколько — см. шапку миграции {@code V2026.08.30_00__create_chat_pending_summary.sql} и
 * {@code PendingSummaryService}, единственного писателя и читателя этой таблицы.
 *
 * <p>Поля позиций и текста — готовый {@code SummaryWriter.SummaryRow}: применение ничего не
 * пересчитывает, оно только записывает. {@code meta} несёт две разные вещи для двух разных рядов:
 * спаны проектов — строке-сводке, замер раунда — видимой плашке.
 */
@Table(name = "chat_pending_summary")
public class ChatPendingSummaryEntity implements Persistable<Long> {

    @Id private long id;
    @NonNull private final String conversationId;
    private final long startPosition;
    private final long endPosition;
    private final long summaryPosition;
    @NonNull private final LocalDateTime summaryCreatedAt;
    @NonNull private final String text;
    private final int messages;
    private final int summaryChars;
    @Nullable private final ChatMessageMeta meta;
    @NonNull private final LocalDateTime createdAt;

    public ChatPendingSummaryEntity(
            long id,
            @NonNull String conversationId,
            long startPosition,
            long endPosition,
            long summaryPosition,
            @NonNull LocalDateTime summaryCreatedAt,
            @NonNull String text,
            int messages,
            int summaryChars,
            @Nullable ChatMessageMeta meta,
            @NonNull LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.summaryPosition = summaryPosition;
        this.summaryCreatedAt = summaryCreatedAt;
        this.text = text;
        this.messages = messages;
        this.summaryChars = summaryChars;
        this.meta = meta;
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

    public long getStartPosition() {
        return startPosition;
    }

    public long getEndPosition() {
        return endPosition;
    }

    public long getSummaryPosition() {
        return summaryPosition;
    }

    @NonNull
    public LocalDateTime getSummaryCreatedAt() {
        return summaryCreatedAt;
    }

    @NonNull
    public String getText() {
        return text;
    }

    public int getMessages() {
        return messages;
    }

    public int getSummaryChars() {
        return summaryChars;
    }

    @Nullable
    public ChatMessageMeta getMeta() {
        return meta;
    }

    @NonNull
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
