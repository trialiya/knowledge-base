package io.github.trialiya.kb.model.doc.dto;

/**
 * Request body for {@code PATCH /api/documents/{id}/move}.
 *
 * <p>Names a target level and ONE neighbour instead of the whole sibling order, so the server can
 * resolve the exact slot from current database state — a partially loaded tree on the client can
 * never corrupt positions.
 *
 * @param parentId target parent id, {@code null} = root level
 * @param afterId sibling to place the node right after, {@code null} = first in the level
 */
public record MoveRequest(Long parentId, Long afterId) {}
