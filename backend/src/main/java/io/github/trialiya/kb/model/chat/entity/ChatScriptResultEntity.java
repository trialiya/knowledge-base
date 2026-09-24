package io.github.trialiya.kb.model.chat.entity;

import java.time.LocalDateTime;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One kept script result of a chat. Why the table exists and why the value is kept whole — see the
 * header of migration {@code V2026.09.24_00__create_chat_script_result.sql} and {@code
 * ChatScriptResults}, its only writer and reader.
 */
@Table(name = "chat_script_result")
public class ChatScriptResultEntity implements Persistable<Long> {

    @Id private long id;
    @NonNull private final String conversationId;
    private final int seq;
    @Nullable private final String script;
    @Nullable private final String project;
    @NonNull private final String valueJson;
    private final int chars;
    @NonNull private final LocalDateTime createdAt;

    public ChatScriptResultEntity(
            long id,
            @NonNull String conversationId,
            int seq,
            @Nullable String script,
            @Nullable String project,
            @NonNull String valueJson,
            int chars,
            @NonNull LocalDateTime createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.seq = seq;
        this.script = script;
        this.project = project;
        this.valueJson = valueJson;
        this.chars = chars;
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

    public int getSeq() {
        return seq;
    }

    @Nullable
    public String getScript() {
        return script;
    }

    @Nullable
    public String getProject() {
        return project;
    }

    @NonNull
    public String getValueJson() {
        return valueJson;
    }

    public int getChars() {
        return chars;
    }

    @NonNull
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
