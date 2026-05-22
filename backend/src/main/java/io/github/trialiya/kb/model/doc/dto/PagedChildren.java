package io.github.trialiya.kb.model.doc.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Paginated children response.
 *
 * <pre>
 * {
 *   "items":       [...],
 *   "totalElements": 42,
 *   "totalPages":    5,
 *   "page":          0,
 *   "size":          10,
 *   "hasNext":       true
 * }
 * </pre>
 */
public record PagedChildren(
        List<DocumentNode> items,
        long totalElements,
        int totalPages,
        int page,
        int size,
        boolean hasNext) {

    /** Convenience factory from a Spring {@link Page} of already-mapped DTOs. */
    public static PagedChildren from(Page<DocumentNode> p) {
        return new PagedChildren(
                p.getContent(),
                p.getTotalElements(),
                p.getTotalPages(),
                p.getNumber(),
                p.getSize(),
                p.hasNext());
    }
}
