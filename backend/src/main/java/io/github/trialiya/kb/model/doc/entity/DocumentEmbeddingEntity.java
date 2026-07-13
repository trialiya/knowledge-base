package io.github.trialiya.kb.model.doc.entity;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("document_embeddings")
public class DocumentEmbeddingEntity {

    @Id @Nullable private Long id;

    /** FK → documents.id (UNIQUE – one embedding per document). */
    private Long documentId;

    /**
     * Raw embedding stored as a {@code float[]} and mapped to the Postgres {@code vector} column
     * via {@link FloatArrayToVectorConverter}.
     */
    private float[] embedding;

    /** Model name used to generate this embedding, e.g. "text-embedding-3-small". */
    private String model;

    private OffsetDateTime updatedAt;
}
