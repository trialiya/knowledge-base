package io.github.trialiya.kb.model.doc.dto;

import java.util.List;
import lombok.Data;

/**
 * Payload for PATCH /api/documents/reorder.
 *
 * <p>The client sends the full ordered list of sibling IDs; the server assigns {@code position =
 * index} for each entry.
 *
 * <pre>
 * {
 *   "parentId": "42",          // null → root level
 *   "orderedIds": ["7","3","1","5"]
 * }
 * </pre>
 */
@Data
public class ReorderRequest {
    /** ID of the parent folder, or {@code null} for root-level items. */
    private String parentId;

    /** Sibling IDs in the desired display order (all siblings must be included). */
    private List<String> orderedIds;
}
