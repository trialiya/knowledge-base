package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ToolCallIndexRepository extends CrudRepository<ToolCallIndexEntity, Long> {

    Optional<ToolCallIndexEntity> findByConversationIdAndCallId(
            String conversationId, String callId);

    List<ToolCallIndexEntity> findAllByConversationId(String conversationId);

    /**
     * Links a call's TOOL response row once it lands. The {@code IS NULL OR <>} guard makes the
     * affected-row count meaningful for idempotency checks (a re-run that changes nothing reports 0
     * rows, not 1).
     */
    @Modifying
    @Query(
            """
            update tool_call_index
               set response_message_id = :responseMessageId
             where conversation_id = :conversationId and call_id = :callId
               and (response_message_id is null or response_message_id <> :responseMessageId)
            """)
    int setResponseMessageId(
            @Param("conversationId") String conversationId,
            @Param("callId") String callId,
            @Param("responseMessageId") long responseMessageId);
}
