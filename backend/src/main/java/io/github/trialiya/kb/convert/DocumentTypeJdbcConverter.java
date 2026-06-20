package io.github.trialiya.kb.convert;

import io.github.trialiya.kb.model.doc.entity.DocumentType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/** Spring Data JDBC converters that map {@link DocumentType} ↔ lowercase {@code VARCHAR}. */
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
            return DocumentType.fromValue(source);
        }
    }
}
