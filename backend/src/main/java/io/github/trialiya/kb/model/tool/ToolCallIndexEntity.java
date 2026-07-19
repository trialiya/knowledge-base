package io.github.trialiya.kb.model.tool;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One row in {@code tool_call_index}: protocol {@code callId} → the {@code chat_message} rows that
 * hold its full details (the ASSISTANT segment that issued it, the TOOL row with its result, if
 * any). Lets {@link io.github.trialiya.kb.service.ChatMemoryService#findToolCallDetail} do a plain
 * lookup by {@code callId} instead of the positional/offset arithmetic the old
 * messageId/callId/responseMessageId-in-meta design needed.
 */
@Data
@NoArgsConstructor
@Table("tool_call_index")
public class ToolCallIndexEntity {

    @Id private Long id;

    private String conversationId;
    private String callId;
    private long messageId;
    @Nullable private Long responseMessageId;
}
