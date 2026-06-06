package io.github.trialiya.kb.model.doc.dto;

import lombok.Data;

@Data
public class CreateDocumentRequest {
    private String title;
    private String type; // "document" | "folder"; если не передан — "document"
    private Long parentId;
    private String description;
}
