package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.tool.ToolCallFullResultEntity;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface ToolCallFullResultRepository extends CrudRepository<ToolCallFullResultEntity, Long> {

    Optional<ToolCallFullResultEntity> findByMessageIdAndCallId(long messageId, String callId);
}
