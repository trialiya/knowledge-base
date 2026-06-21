package io.github.trialiya.kb.model.tool;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table("tool_call")
public class ToolCallEntity implements Persistable<Long> {

    @Id private long id;
    private final String conversationId;
    private final String runId;
    private final int callIndex;
    private final String name;
    @Nullable private final String argumentsRaw;
    private final String status;
    @Nullable private final String error;
    @Nullable private final String resultText;
    @Nullable private final String resultMeta;
    private final LocalDateTime createdAt;

    public ToolCallEntity(
            String conversationId,
            String runId,
            int callIndex,
            String name,
            @Nullable String argumentsRaw,
            String status,
            @Nullable String error,
            @Nullable String resultText,
            @Nullable String resultMeta) {
        this.id = 0L;
        this.conversationId = conversationId;
        this.runId = runId;
        this.callIndex = callIndex;
        this.name = name;
        this.argumentsRaw = argumentsRaw;
        this.status = status;
        this.error = error;
        this.resultText = resultText;
        this.resultMeta = resultMeta;
        this.createdAt = LocalDateTime.now();
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return id == 0L;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getRunId() {
        return runId;
    }

    public int getCallIndex() {
        return callIndex;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public String getArgumentsRaw() {
        return argumentsRaw;
    }

    public String getStatus() {
        return status;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Nullable
    public String getResultText() {
        return resultText;
    }

    @Nullable
    public String getResultMeta() {
        return resultMeta;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
