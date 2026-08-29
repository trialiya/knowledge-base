package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends CrudRepository<ChatMessageEntity, Long> {

    int deleteChatMessageByConversationId(String conversationId);

    List<ChatMessageEntity>
            findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                    @Param("conversationId") String conversationId);

    List<ChatMessageEntity>
            findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
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

    Optional<ChatMessageEntity> findFirstByConversationIdOrderByPositionDesc(String conversationId);

    /**
     * Весь чат целиком, включая строки-сводки и уже сжатые ряды, — для разовых проходов по истории
     * ({@code ProjectStampBackfill}). Обычному чтению это не нужно: и промпту, и UI нужна половина
     * чата, и обе половины отбирают запросы выше.
     */
    List<ChatMessageEntity> findByConversationIdOrderByPositionAsc(String conversationId);

    /**
     * Хвост чата, свежее первым, — для читателей, которым мало последнего ряда: ряды git-команд
     * стоят между вопросом и ответом и не отвечают ни за то, ни за другое, поэтому «последний
     * вопрос» приходится искать сквозь них (см. {@code ChatHistoryService.unansweredUserMessage}).
     */
    List<ChatMessageEntity> findTop20ByConversationIdOrderByPositionDesc(String conversationId);

    /** Максимальная позиция в чате, 0 для пустого — источник номера для нового ряда. */
    @Query(
            """
    SELECT COALESCE(MAX(position), 0) FROM chat_message
    WHERE conversation_id = :conversationId
    """)
    long maxPosition(@Param("conversationId") String conversationId);

    @Query(
            """
    SELECT * FROM chat_message
    WHERE conversation_id = :conversationId AND summary = false
    ORDER BY created_at DESC, id DESC
    LIMIT :limit
    """)
    List<ChatMessageEntity> findLatest(
            @Param("conversationId") String conversationId, @Param("limit") int limit);

    @Query(
            """
    SELECT * FROM chat_message
    WHERE conversation_id = :conversationId AND summary = false
      AND (created_at < :beforeCreatedAt
           OR (created_at = :beforeCreatedAt AND id < :beforeId))
    ORDER BY created_at DESC, id DESC
    LIMIT :limit
    """)
    List<ChatMessageEntity> findBefore(
            @Param("conversationId") String conversationId,
            @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
            @Param("beforeId") long beforeId,
            @Param("limit") int limit);

    /** Совпадения по тексту сообщения внутри чата, хронологически (для find-бара, Ctrl+F). */
    @Query(
            """
    SELECT * FROM chat_message
    WHERE conversation_id = :conversationId AND summary = false
      AND content ILIKE '%' || :q || '%'
    ORDER BY created_at ASC, id ASC
    """)
    List<ChatMessageEntity> searchInConversation(
            @Param("conversationId") String conversationId, @Param("q") String q);

    /** Совпадения по тексту сообщений среди всех чатов пользователя (поиск по чатам). */
    @Query(
            """
    SELECT cm.* FROM chat_message cm
    JOIN chat_topic ct ON ct.conversation_id = cm.conversation_id
    WHERE ct."user" = :user AND cm.summary = false
      AND cm.content ILIKE '%' || :q || '%'
    ORDER BY cm.created_at DESC, cm.id DESC
    LIMIT :limit
    """)
    List<ChatMessageEntity> searchForUser(
            @Param("user") String user, @Param("q") String q, @Param("limit") int limit);
}
