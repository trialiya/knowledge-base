package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class DocumentNode {
    private final String id;
    private final String title;
    private final String type;
    private final String parentId;
    private final String description;
    private final LocalDateTime updatedAt;
    private final List<DocumentNode> children;
    private final boolean hasChildren;
}
