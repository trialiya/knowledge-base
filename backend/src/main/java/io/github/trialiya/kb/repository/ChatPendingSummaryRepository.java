package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.chat.entity.ChatPendingSummaryEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ChatPendingSummaryRepository
        extends CrudRepository<ChatPendingSummaryEntity, Long> {

    /**
     * Очередь неприменённых сводок чата, в порядке сжатых ими кусков — в том же порядке они и
     * применяются: каждая следующая описывает то, что накопилось за предыдущей.
     */
    List<ChatPendingSummaryEntity> findByConversationIdOrderByStartPositionAsc(
            String conversationId);

    /**
     * Заявка на применение — claim-through-delete, как у очереди сообщений: применяет тот, чей
     * DELETE строку застал. Точек применения несколько (пауза перед новым вопросом, размер
     * контекста в конце прогона), и без заявки две из них записали бы одну сводку дважды.
     *
     * @return {@code 0} — строку забрал кто-то другой, и применять нечего
     */
    @Modifying
    @Query("DELETE FROM chat_pending_summary WHERE id = :id")
    int claim(@Param("id") long id);

    @Modifying
    @Query("DELETE FROM chat_pending_summary WHERE conversation_id = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);
}
