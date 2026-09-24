package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatScriptResultEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatScriptResultRepository extends CrudRepository<ChatScriptResultEntity, Long> {

    Optional<ChatScriptResultEntity> findByConversationIdAndSeq(String conversationId, int seq);

    /**
     * The chat's kept results without their values, oldest first — what a listing needs, and
     * nothing that could be a megabyte per row.
     */
    @Query(
            """
            SELECT id, conversation_id, seq, script, project, '' AS value_json, chars, created_at
            FROM chat_script_result
            WHERE conversation_id = :conversationId
            ORDER BY seq
            """)
    List<ChatScriptResultEntity> listWithoutValues(@Param("conversationId") String conversationId);

    @Query(
            "SELECT COALESCE(MAX(seq), 0) FROM chat_script_result"
                    + " WHERE conversation_id = :conversationId")
    int maxSeq(@Param("conversationId") String conversationId);

    /** Drops every result of the chat numbered at or below {@code seq}. */
    @Modifying
    @Query(
            "DELETE FROM chat_script_result"
                    + " WHERE conversation_id = :conversationId AND seq <= :seq")
    int deleteUpTo(@Param("conversationId") String conversationId, @Param("seq") int seq);
}
