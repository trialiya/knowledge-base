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

    /**
     * When true: the UI must hide delete/rename controls and the server will reject delete/rename
     * requests with 403.
     */
    private final boolean system;
}
