package io.github.trialiya.kb.model.doc.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class UpdateDocumentRequest {
    @Nullable private String title; // опционально — можно переименовать
    @Nullable private String description; // для папок и документов
}
