package io.github.trialiya.kb.model.attachment.entity;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One embedding vector per attachment, analogous to {@link
 * io.github.trialiya.kb.model.doc.entity.DocumentEmbeddingEntity}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("attachment_embeddings")
public class AttachmentEmbeddingEntity {

    @Id @Nullable private Long id;

    /** FK → attachments.id (UNIQUE — one embedding per attachment). */
    private Long attachmentId;

    /** Raw embedding vector stored as pgvector. */
    private float[] embedding;

    /** Model name that produced this embedding. */
    private String model;

    private OffsetDateTime updatedAt;
}
