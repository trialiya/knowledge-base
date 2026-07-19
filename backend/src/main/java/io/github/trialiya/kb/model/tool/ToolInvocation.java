package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        @Nullable String error,
        @JsonIgnore @Nullable Map<String, ?> resultMeta,
        @Nullable String resultGist,
        @JsonIgnore String argumentsRaw,
        @JsonIgnore @Nullable String resultText,
        int callIndex) {

    @JsonIgnore
    public ToolInvocationMeta toMeta(boolean hasDetails) {
        return toMeta(hasDetails, null, null, null);
    }

    /**
     * @param messageId id ASSISTANT-сообщения, сохранившего этот вызов (см. {@link
     *     ToolInvocationMeta#messageId()})
     * @param callId протокольный id вызова, если он уже известен (сопоставлен позиционно с {@code
     *     tool_data.toolCalls} — {@code RecordingToolCallback} его не видит)
     * @param responseMessageId id TOOL-сообщения с ответом на этот вызов, если он уже известен
     */
    @JsonIgnore
    public ToolInvocationMeta toMeta(
            boolean hasDetails,
            @Nullable Long messageId,
            @Nullable String callId,
            @Nullable Long responseMessageId) {
        return new ToolInvocationMeta(
                name,
                arguments,
                status,
                error,
                resultMeta,
                hasDetails,
                callIndex,
                resultGist,
                messageId,
                callId,
                responseMessageId);
    }
}
