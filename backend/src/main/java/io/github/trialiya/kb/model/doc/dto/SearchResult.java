package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SearchResult(
        String id,
        String title,
        String snippet,
        LocalDateTime updatedAt,
        String summary,
        List<Parent> parentList) {

    public record Parent(String id, String title) {}
}
