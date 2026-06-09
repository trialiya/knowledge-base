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

    @Modifying
    @Query("UPDATE chat_topic SET updated_at = CURRENT_TIMESTAMP WHERE conversation_id = :convId")
    void updateUpdatedAt(@Param("convId") String convId);

    @Modifying
    @Query("UPDATE chat_topic SET model = :model WHERE conversation_id = :convId")
    void updateModel(@Param("convId") String convId, @Param("model") String model);
}
