package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends CrudRepository<ChatMessageEntity, String> {

    int deleteChatMessageByConversationId(String conversationId);

    List<ChatMessageEntity> findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAt(
            @Param("conversationId") String conversationId);

    List<ChatMessageEntity> findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAt(
            @Param("conversationId") String conversationId);

    // findSummaries
    List<ChatMessageEntity>
            findChatMessageByConversationIdAndSummarizedFalseAndSummaryTrueOrderByCreatedAt(
                    @Param("conversationId") String conversationId);

    @Modifying
    @Query(
            """
           update chat_message
           set summarized = true
           where conversation_id = :conversationId and
                 position between :startPosition and :endPosition
           """)
    int updateSummarized(
            @Param("conversationId") String conversationId,
            @Param("startPosition") long startPosition,
            @Param("endPosition") long endPosition);

    List<ChatMessageEntity> findChatMessagesByConversationIdAndPositionInOrderByCreatedAt(
            @Param("conversationId") String conversationId,
            @Param("positions") List<Long> positions);

    Optional<ChatMessageEntity> findFirstByConversationIdOrderByCreatedAtDesc(
            String conversationId);
}
