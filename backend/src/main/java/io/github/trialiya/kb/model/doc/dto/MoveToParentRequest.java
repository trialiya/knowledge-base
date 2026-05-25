package io.github.trialiya.kb.model.doc.dto;

import lombok.Data;

/**
 * Request body for {@code PATCH /api/documents/{id}/parent}. {@code parentId} is the target folder
 * id, or {@code null} to move to the root level.
 */
@Data
public class MoveToParentRequest {
    private String parentId; // nullable → root
}
