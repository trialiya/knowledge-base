package io.github.trialiya.kb.model.doc.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CreateDocumentRequest {
    private String title;
    @Nullable private String type; // "document" | "folder"; если не передан — "document"
    @Nullable private Long parentId;
    @Nullable private String description;
}
