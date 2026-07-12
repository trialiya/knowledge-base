package io.github.trialiya.kb.convert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

public final class ChatMessageMetaToJsonConverter {

    /** Projection for reading the new object format {"runId":"...","invocations":[...]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetaJson(
            @Nullable String runId,
            @Nullable Boolean toolCalls,
            List<ToolInvocationMeta> invocations) {}

    @ReadingConverter
    public static class Reader implements Converter<String, ChatMessageMeta> {

        private static final TypeReference<List<ToolInvocationMeta>> LIST_TYPE =
                new TypeReference<>() {};

        private final ObjectMapper objectMapper;

        public Reader(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ChatMessageMeta convert(String source) {
            try {
                String trimmed = source.trim();
                if (trimmed.startsWith("[")) {
                    // Legacy format: bare array — это всегда «крошки» вызовов инструментов.
                    return new ChatMessageMeta(objectMapper.readValue(trimmed, LIST_TYPE));
                }
                // New format: {"runId":"...","toolCalls":true,"invocations":[...]}. Поле toolCalls
                // проставлено и в новых записях, и в старых (см. миграцию backfill).
                MetaJson json = objectMapper.readValue(trimmed, MetaJson.class);
                return new ChatMessageMeta(
                        json.runId(), Boolean.TRUE.equals(json.toolCalls()), json.invocations());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to deserialize chat message meta", e);
            }
        }
    }

    @WritingConverter
    public static class Writer implements Converter<ChatMessageMeta, String> {

        private final ObjectMapper objectMapper;

        public Writer(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String convert(ChatMessageMeta source) {
            try {
                return objectMapper.writeValueAsString(
                        new MetaJson(source.runId(), source.toolCalls(), source.invocations()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize chat message meta", e);
            }
        }
    }
}
