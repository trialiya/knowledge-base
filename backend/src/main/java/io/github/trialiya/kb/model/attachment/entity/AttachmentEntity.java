package io.github.trialiya.kb.model.attachment.entity;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Persistent entity for a text-file attachment.
 *
 * <p>An attachment belongs to exactly one owner: either a {@code document} (via {@code documentId})
 * or a {@code chat} conversation (via {@code conversationId}). The {@code ownerType} discriminator
 * encodes which foreign key is active.
 *
 * <p>For text-based files the raw content is stored inline in {@link #content}. Binary files are
 * <em>not yet supported</em>; the column is nullable so it can be extended later.
 *
 * <p>{@link #summary} holds an AI-generated description of the file, populated on demand via {@link
 * io.github.trialiya.kb.service.AttachmentService#summarize}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("attachments")
public class AttachmentEntity {

    @Id private Long id;

    /** Discriminator: {@code "document"} or {@code "chat"}. */
    private String ownerType;

    /** FK → documents.id (non-null when ownerType = 'document'). */
    private Long documentId;

    /** FK → chat_topic.conversation_id (non-null when ownerType = 'chat'). */
    private String conversationId;

    // ── File metadata ────────────────────────────────────────────────────────

    /** Original file name as uploaded by the user. */
    private String fileName;

    /** MIME type, e.g. {@code text/plain}, {@code text/markdown}. */
    private String contentType;

    /** File size in bytes. */
    private long fileSize;

    // ── Content ──────────────────────────────────────────────────────────────

    /** Raw text content (for text-based files). */
    private String content;

    /** AI-generated summary / description. */
    private String summary;

    // ── Timestamps ───────────────────────────────────────────────────────────

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
