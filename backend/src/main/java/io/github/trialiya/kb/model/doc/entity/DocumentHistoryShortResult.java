package io.github.trialiya.kb.model.doc.entity;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

public record DocumentHistoryShortResult(
        long id,
        Long documentId,
        int version,
        String title,
        String type,
        LocalDateTime updatedAt,
        @Nullable Integer summarySourceVersion,
        int descriptionVersion) {}
