package io.github.trialiya.kb.model.doc.entity;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Structural projection of a {@code documents} row: everything needed to place a node in the tree
 * and name its file, and <b>nothing else</b>.
 *
 * <p>The point is the column that is missing. {@code description} holds the entire body of a
 * document, so any tree-wide walk built on {@link DocumentEntity} pulls the whole knowledge base
 * into the heap. Export, download and sync walk the tree structurally first and only then fetch one
 * body at a time ({@code DocumentRepository.findDescriptionById}), which keeps their memory
 * proportional to the number of nodes rather than to the size of the content.
 */
public record DocumentTreeRow(
        long id,
        @Nullable Long parentId,
        String title,
        DocumentType type,
        int position,
        boolean isSystem,
        @Nullable LocalDateTime updatedAt) {

    public boolean isFolder() {
        return type == DocumentType.FOLDER;
    }
}
