package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.tool.ToolCallEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ToolCallRepository extends CrudRepository<ToolCallEntity, Long> {

    List<ToolCallEntity> findByConversationIdAndRunIdOrderByCallIndex(
            @Param("conversationId") String conversationId, @Param("runId") String runId);

    Optional<ToolCallEntity> findByConversationIdAndRunIdAndCallIndex(
            @Param("conversationId") String conversationId,
            @Param("runId") String runId,
            @Param("callIndex") int callIndex);
}
