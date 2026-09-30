package io.github.trialiya.kb.model.tool;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row in {@code tool_call_full_result}: a tool's result whole, where the model was answered
 * with a trimmed view of it ({@link ModelView}). Kept apart from {@code chat_message.tool_data} on
 * purpose — that column is what the model is shown on every replay of the history, and this text
 * is the one it must not be shown. Read only for the call's detail view.
 */
@Data
// Hydrated by Spring Data JDBC via the no-args constructor + setters; see DocumentEmbeddingEntity.
@SuppressWarnings("NullAway.Init")
@NoArgsConstructor
@Table("tool_call_full_result")
public class ToolCallFullResultEntity {

    @Id
    private Long id;

    /** The TOOL row whose response this result belongs to. */
    private long messageId;

    private String callId;
    private String resultText;
}
