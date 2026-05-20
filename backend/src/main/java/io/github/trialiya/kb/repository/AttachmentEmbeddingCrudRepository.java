package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.attachment.entity.AttachmentEmbeddingEntity;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface AttachmentEmbeddingCrudRepository
        extends CrudRepository<AttachmentEmbeddingEntity, Long> {

    Optional<AttachmentEmbeddingEntity> findByAttachmentId(Long attachmentId);

    void deleteByAttachmentId(Long attachmentId);
}
