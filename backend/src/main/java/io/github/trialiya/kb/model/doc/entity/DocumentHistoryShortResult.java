package io.github.trialiya.kb.model.doc.entity;

import java.time.LocalDateTime;

public record DocumentHistoryShortResult(
        long id,
        Long documentId,
        int version,
        String title,
        String type,
        LocalDateTime updatedAt,
        Integer summarySourceVersion,
        int descriptionVersion) {}
