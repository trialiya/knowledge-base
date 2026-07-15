package io.github.trialiya.kb.convert;

import io.github.trialiya.kb.model.attachment.entity.AttachmentOwnerType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/** Spring Data JDBC converters that map {@link AttachmentOwnerType} ↔ lowercase {@code VARCHAR}. */
public final class AttachmentOwnerTypeJdbcConverter {

    private AttachmentOwnerTypeJdbcConverter() {}

    @WritingConverter
    public static class Writer implements Converter<AttachmentOwnerType, String> {
        @Override
        public String convert(AttachmentOwnerType source) {
            return source.getValue();
        }
    }

    @ReadingConverter
    public static class Reader implements Converter<String, AttachmentOwnerType> {
        @Override
        public AttachmentOwnerType convert(String source) {
            return AttachmentOwnerType.fromValue(source);
        }
    }
}
