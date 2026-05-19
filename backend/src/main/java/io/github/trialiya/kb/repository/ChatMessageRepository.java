package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends CrudRepository<ChatMessage, String> {

    int deleteChatMessageByConversationId(String conversationId);

    List<ChatMessage> findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAt(
            @Param("conversationId") String conversationId);

    List<ChatMessage> findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAt(
            @Param("conversationId") String conversationId);

    // findSummaries
    List<ChatMessage>
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

    List<ChatMessage> findChatMessagesByConversationIdAndPositionInOrderByCreatedAt(
            @Param("conversationId") String conversationId,
            @Param("positions") List<Long> positions);

    Optional<ChatMessage> findFirstByConversationIdOrderByCreatedAtDesc(String conversationId);
}
