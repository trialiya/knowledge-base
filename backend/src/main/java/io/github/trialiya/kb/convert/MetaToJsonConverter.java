package io.github.trialiya.kb.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class MetaToJsonConverter implements Converter<ChatMessageMeta, String> {

    private final ObjectMapper objectMapper;

    public MetaToJsonConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String convert(ChatMessageMeta source) {
        try {
            return objectMapper.writeValueAsString(source.invocations());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat message meta", e);
        }
    }
}
