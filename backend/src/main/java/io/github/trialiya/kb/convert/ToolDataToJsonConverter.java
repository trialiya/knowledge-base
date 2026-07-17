package io.github.trialiya.kb.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.tool.ToolData;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/** JSON-конвертеры колонки {@code chat_message.tool_data} (см. {@link ToolData}). */
public final class ToolDataToJsonConverter {

    @ReadingConverter
    public static class Reader implements Converter<String, ToolData> {

        private final ObjectMapper objectMapper;

        public Reader(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolData convert(String source) {
            try {
                return objectMapper.readValue(source, ToolData.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize chat tool data", e);
            }
        }
    }

    @WritingConverter
    public static class Writer implements Converter<ToolData, String> {

        private final ObjectMapper objectMapper;

        public Writer(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String convert(ToolData source) {
            try {
                return objectMapper.writeValueAsString(source);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize chat tool data", e);
            }
        }
    }
}
