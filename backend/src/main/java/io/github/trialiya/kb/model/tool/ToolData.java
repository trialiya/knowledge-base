package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Протокольные данные tool-цикла, хранящиеся в колонке {@code chat_message.tool_data}. Ровно то,
 * что нужно для восстановления сообщения в формате OpenAI: у ASSISTANT-сообщения — список
 * tool_calls, у TOOL-сообщения — список ответов инструментов. В отличие от {@link
 * ToolInvocationMeta} (усечённые «крошки» для UI) здесь ответ ровно в том виде, в каком его получила
 * модель, — она должна видеть его таким же на следующих итерациях цикла.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolData(
        @Nullable List<Call> toolCalls, @Nullable List<Response> responses) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Call(String id, String type, String name, String arguments) {}

    /**
     * @param responseData чем ответили модели — единственный текст, из которого собирается промпт
     * @param fullData результат целиком, когда {@code responseData} — его урезанный вид ({@link
     *     ModelView}); только для окна деталей вызова, модели не уходит никогда. {@code null} —
     *     модель получила результат целиком
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            String id,
            String name,
            String responseData,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String fullData) {

        public Response(String id, String name, String responseData) {
            this(id, name, responseData, null);
        }

        /** Тот же ответ с результатом целиком; {@code null} оставляет ответ как есть. */
        public Response withFullData(@Nullable String full) {
            return full == null ? this : new Response(id, name, responseData, full);
        }
    }

    public static ToolData from(AssistantMessage message) {
        return new ToolData(
                message.getToolCalls().stream()
                        .map(tc -> new Call(tc.id(), tc.type(), tc.name(), tc.arguments()))
                        .toList(),
                null);
    }

    public static ToolData from(ToolResponseMessage message) {
        return new ToolData(
                null,
                message.getResponses().stream()
                        .map(tr -> new Response(tr.id(), tr.name(), tr.responseData()))
                        .toList());
    }

    public List<AssistantMessage.ToolCall> toToolCalls() {
        return toolCalls == null
                ? List.of()
                : toolCalls.stream()
                        .map(c -> new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments()))
                        .toList();
    }

    public List<ToolResponseMessage.ToolResponse> toToolResponses() {
        return responses == null
                ? List.of()
                : responses.stream()
                        .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData()))
                        .toList();
    }
}
