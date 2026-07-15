package io.github.trialiya.kb.model.doc.dto;

import io.github.trialiya.kb.model.doc.entity.DocumentType;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CreateDocumentRequest {
    private String title;
    @Nullable private DocumentType type;
    @Nullable private Long parentId;
    @Nullable private String description;
}
