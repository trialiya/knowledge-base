package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface EmbeddingCrudRepository extends CrudRepository<DocumentEmbeddingEntity, Long> {

    Optional<DocumentEmbeddingEntity> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
