package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatTopicRepository extends CrudRepository<ChatTopicEntity, String> {

    // Найти все темы для конкретной беседы и роли (пользователь/система)
    List<ChatTopicEntity> findAllByUserOrderByUpdatedAtDesc(String user);

    List<String> findIdsByUserOrderByUpdatedAtDesc(String user);

    /** Все id разговоров — для одноразовых админ-задач (напр. бэкафилл), не для обычного UI. */
    @Query("SELECT conversation_id FROM chat_topic")
    List<String> findAllConversationIds();

    @Modifying
    @Query("UPDATE chat_topic SET updated_at = CURRENT_TIMESTAMP WHERE conversation_id = :convId")
    void updateUpdatedAt(@Param("convId") String convId);

    @Modifying
    @Query("UPDATE chat_topic SET model = :model WHERE conversation_id = :convId")
    void updateModel(@Param("convId") String convId, @Param("model") String model);

    @Modifying
    @Query("UPDATE chat_topic SET mode = :mode WHERE conversation_id = :convId")
    void updateMode(@Param("convId") String convId, @Param("mode") String mode);

    /** Чаты пользователя, чьё название содержит q (поиск по чатам). */
    @Query(
            """
    SELECT * FROM chat_topic
    WHERE "user" = :user AND topic ILIKE '%' || :q || '%'
    ORDER BY updated_at DESC
    """)
    List<ChatTopicEntity> searchByTopic(@Param("user") String user, @Param("q") String q);
}
