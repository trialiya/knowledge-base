package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;

/**
 * Read-only DTO for a single history entry returned to the client.
 *
 * @param documentId id of the document this snapshot belongs to
 * @param version snapshot version (always < current documents.version)
 * @param title title at the time of the snapshot
 * @param type type at the time of the snapshot
 * @param description description at the time of the snapshot
 * @param updatedAt timestamp when the document had this content
 */
public record DocumentHistoryEntry(
        String documentId,
        int version,
        String title,
        String type,
        String description,
        LocalDateTime updatedAt,
        String summary) {}
