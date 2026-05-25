package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SearchResult {
    private final String id;
    private final String title;
    private final String snippet;
    private final LocalDateTime updatedAt;
    private final String summary;
}
