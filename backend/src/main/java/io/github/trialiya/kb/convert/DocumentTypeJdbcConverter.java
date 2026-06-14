package io.github.trialiya.kb.convert;

import io.github.trialiya.kb.model.doc.DocumentType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * Maps {@link DocumentType} to/from the lowercase string stored in {@code documents.type}. Without
 * this, Spring Data JDBC would persist the enum by {@code name()} (upper-case), breaking existing
 * lowercase rows.
 */
public final class DocumentTypeJdbcConverter {

    private DocumentTypeJdbcConverter() {}

    @WritingConverter
    public static class Writer implements Converter<DocumentType, String> {
        @Override
        public String convert(DocumentType source) {
            return source.getValue();
        }
    }

    @ReadingConverter
    public static class Reader implements Converter<String, DocumentType> {
        @Override
        public DocumentType convert(String source) {
            return DocumentType.fromString(source);
        }
    }
}
