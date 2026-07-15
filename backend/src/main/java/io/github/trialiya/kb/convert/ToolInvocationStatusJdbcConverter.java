package io.github.trialiya.kb.convert;

import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/** Spring Data JDBC converters that map {@link ToolInvocationStatus} ↔ uppercase {@code VARCHAR}. */
public final class ToolInvocationStatusJdbcConverter {

    private ToolInvocationStatusJdbcConverter() {}

    @WritingConverter
    public static class Writer implements Converter<ToolInvocationStatus, String> {
        @Override
        public String convert(ToolInvocationStatus source) {
            return source.name();
        }
    }

    @ReadingConverter
    public static class Reader implements Converter<String, ToolInvocationStatus> {
        @Override
        public ToolInvocationStatus convert(String source) {
            return ToolInvocationStatus.valueOf(source);
        }
    }
}
