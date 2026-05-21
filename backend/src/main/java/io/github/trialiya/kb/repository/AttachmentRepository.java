package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.attachment.entity.AttachmentEntity;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends CrudRepository<AttachmentEntity, Long> {

    /** All attachments for a given document, ordered by creation time. */
    @Query("SELECT * FROM attachments WHERE document_id = :docId ORDER BY created_at")
    List<AttachmentEntity> findByDocumentId(@Param("docId") Long documentId);

    /** All attachments for a given chat conversation, ordered by creation time. */
    @Query("SELECT * FROM attachments WHERE conversation_id = :convId ORDER BY created_at")
    List<AttachmentEntity> findByConversationId(@Param("convId") String conversationId);

    /** Delete all attachments belonging to a document (used on document cascade). */
    void deleteByDocumentId(Long documentId);

    /** Delete all attachments belonging to a conversation. */
    void deleteByConversationId(String conversationId);

    /** Full-text search across file name, content and summary. */
    @Query(
            """
        SELECT * FROM attachments
        WHERE file_name ILIKE '%' || :q || '%'
           OR content   ILIKE '%' || :q || '%'
           OR summary   ILIKE '%' || :q || '%'
        ORDER BY updated_at DESC
        LIMIT 20
        """)
    List<AttachmentEntity> search(@Param("q") String q);

    /** Full-text search across file name, content and summary. */
    @Query(
            """
        SELECT *
        FROM attachments
        WHERE conversation_id = :conversationId
           AND (file_name ILIKE '%' || :q || '%'
           OR content   ILIKE '%' || :q || '%'
           OR summary   ILIKE '%' || :q || '%')
        ORDER BY updated_at DESC
        LIMIT 20
        """)
    List<AttachmentEntity> search(
            @Param("conversationId") String conversationId, @Param("q") String q);
}
