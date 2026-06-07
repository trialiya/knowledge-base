package io.github.trialiya.kb.convert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.tools.ToolInvocationMeta;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class JsonToMetaConverter implements Converter<String, ChatMessageMeta> {

    private static final TypeReference<List<ToolInvocationMeta>> TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public JsonToMetaConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatMessageMeta convert(String source) {
        try {
            return new ChatMessageMeta(objectMapper.readValue(source, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize chat message meta", e);
        }
    }
}
