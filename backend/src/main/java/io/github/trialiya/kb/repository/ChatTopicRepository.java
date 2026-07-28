package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import java.time.LocalDateTime;
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

    /**
     * «Тронуть» чат: поднять его наверх списка. Время передаётся параметром, а не берётся из {@code
     * clock_timestamp()}: остальные записи в {@code updated_at} делает аудит Spring Data ({@link
     * io.github.trialiya.kb.config.JdbcConfig#dateTimeProvider}), и смешивать два источника времени
     * нельзя — при расхождении часов приложения и БД свежий чат уезжает вниз списка. Бонусом запрос
     * перестаёт зависеть от постгресовой функции и работает на H2.
     *
     * <p>Перегрузки без параметра здесь намеренно нет: {@code LocalDateTime.now()} внутри
     * репозитория — это снова второй источник времени, просто уже не такой заметный. Часы
     * вызывающего обязаны быть теми же, что у аудита, — то есть бином {@link
     * io.github.trialiya.kb.config.JdbcConfig#clock()}.
     */
    @Modifying
    @Query("UPDATE chat_topic SET updated_at = :now WHERE conversation_id = :convId")
    void updateUpdatedAt(@Param("convId") String convId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE chat_topic SET model = :model WHERE conversation_id = :convId")
    void updateModel(@Param("convId") String convId, @Param("model") String model);

    @Modifying
    @Query("UPDATE chat_topic SET mode = :mode WHERE conversation_id = :convId")
    void updateMode(@Param("convId") String convId, @Param("mode") String mode);

    /**
     * Чаты пользователя, чьё название содержит q (поиск по чатам). Ищет по отображаемому названию —
     * пользовательское имя, если задано, иначе предложенное ИИ (см. {@link
     * io.github.trialiya.kb.model.chat.entity.ChatTopicEntity#getDisplayTopic()}).
     */
    @Query(
            """
    SELECT * FROM chat_topic
    WHERE "user" = :user AND COALESCE(user_topic, ai_topic) ILIKE '%' || :q || '%'
    ORDER BY updated_at DESC
    """)
    List<ChatTopicEntity> searchByTopic(@Param("user") String user, @Param("q") String q);
}
