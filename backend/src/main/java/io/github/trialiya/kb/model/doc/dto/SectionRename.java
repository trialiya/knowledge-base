package io.github.trialiya.kb.model.doc.dto;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * One heading rename for {@link
 * io.github.trialiya.kb.functions.DocumentFunction#renameDocumentSections}.
 */
public record SectionRename(
        @ToolParam(description = "Путь секции из getDocumentOutline") String sectionPath,
        @ToolParam(description = "Новый текст заголовка (без ведущих #)") String newTitle) {}
