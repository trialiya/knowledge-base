package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatPendingMessageEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatPendingMessageRepository
        extends CrudRepository<ChatPendingMessageEntity, Long> {

    /** Очередь чата в порядке отправки — id монотонен, отдельной позиции строкам не нужно. */
    List<ChatPendingMessageEntity> findByConversationIdOrderByIdAsc(String conversationId);

    /**
     * Заявка на доставку строки — claim-through-delete: строку доставляет тот, чей DELETE её
     * застал. Возврат — число затронутых строк: {@code 0} значит «другая точка доставки успела
     * первой», и вызывающий обязан молча пропустить строку, а не доставлять её второй раз.
     */
    @Modifying
    @Query("DELETE FROM chat_pending_message WHERE id = :id")
    int claim(@Param("id") long id);

    /** Чаты с недоставленными строками — для восстановления после падения процесса. */
    @Query("SELECT DISTINCT conversation_id FROM chat_pending_message")
    List<String> conversationIds();
}
