package io.github.trialiya.kb.model.doc.dto;

import lombok.Data;

@Data
public class UpdateDocumentRequest {
    private String title; // опционально — можно переименовать
    private String description; // для папок и документов
}
