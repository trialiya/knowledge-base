package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.chat.spring.AssistantChatMessage;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.chat.spring.SystemChatMessage;
import io.github.trialiya.kb.model.chat.spring.ToolChatMessage;
import io.github.trialiya.kb.model.chat.spring.UserChatMessage;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "chat_message")
public class ChatMessageEntity implements Message, Persistable<Long> {

    @Id private long id;
    @NonNull private final String conversationId;
    @NonNull private final String content;
    @NonNull private final MessageType type;
    private final long position;
    private final boolean summarized;
    private final boolean summary;
    @NonNull private final LocalDateTime createdAt;
    @Nullable private final ChatMessageMeta meta;

    /** Протокольные tool-данные (tool_calls / responses) — см. {@link ToolData}. */
    @Nullable private final ToolData toolData;

    @PersistenceCreator
    public ChatMessageEntity(
            long id,
            @NonNull String conversationId,
            @NonNull String content,
            @NonNull MessageType type,
            long position,
            boolean summarized,
            boolean summary,
            @NonNull LocalDateTime createdAt,
            @Nullable ChatMessageMeta meta,
            @Nullable ToolData toolData) {
        this.id = id;
        this.conversationId = conversationId;
        this.content = content;
        this.type = type;
        this.position = position;
        this.summarized = summarized;
        this.summary = summary;
        this.createdAt = createdAt;
        this.meta = meta;
        this.toolData = toolData;
    }

    public ChatMessageEntity(
            long id,
            @NonNull String conversationId,
            @NonNull String content,
            @NonNull MessageType type,
            long position,
            boolean summarized,
            boolean summary,
            @NonNull LocalDateTime createdAt,
            @Nullable ChatMessageMeta meta) {
        this(
                id,
                conversationId,
                content,
                type,
                position,
                summarized,
                summary,
                createdAt,
                meta,
                null);
    }

    /** Копия с проставленными метаданными — для пост-обогащения сегментов прогона. */
    public ChatMessageEntity withMeta(@Nullable ChatMessageMeta newMeta) {
        return new ChatMessageEntity(
                id,
                conversationId,
                content,
                type,
                position,
                summarized,
                summary,
                createdAt,
                newMeta,
                toolData);
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

    @NonNull
    public String getConversationId() {
        return conversationId;
    }

    @NonNull
    public String getContent() {
        return content;
    }

    @NonNull
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

    @NonNull
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public ChatMessageMeta getMeta() {
        return meta;
    }

    @Nullable
    public ToolData getToolData() {
        return toolData;
    }

    @Nullable
    public List<ToolInvocationMeta> getInvocations() {
        return meta != null ? meta.invocations() : null;
    }

    /**
     * Замер прогона, кончившегося этим рядом, — то есть замер, описывающий контекст чата после
     * него. {@code null} — замера нет, он не измерен вовсе ({@link RunTokenUsage#isEmpty}) либо он
     * не про контекст.
     *
     * <p>Не про контекст он на ряду ПОЛЬЗОВАТЕЛЯ: там замер бывает у одного случая —
     * несостоявшегося сжатия, записанного на строку своей команды ({@code
     * CompactService#spentRound}), — и описывает окно, которое раунд прочитал вместе со своей
     * инструкцией, при том что само окно осталось в чате как было. В счёт провайдера такой замер
     * идёт наравне со всеми, а в «сколько занято» — нет. То же правило на фронте ({@code runUsage}
     * в {@code tokenUsage.js}), и разойтись им нельзя: по одному ряду вышло бы два разных ответа на
     * вопрос «сколько тут контекста».
     */
    public @Nullable RunTokenUsage getRunUsage() {
        if (type == MessageType.USER || meta == null || meta.usage() == null) {
            return null;
        }
        return meta.usage().isEmpty() ? null : meta.usage();
    }

    /** Приложенное к сообщению (вложения и т.п.); пустой список, если меты нет. */
    public List<ContextItem> getContextItems() {
        return meta != null ? meta.contextItems() : List.of();
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
