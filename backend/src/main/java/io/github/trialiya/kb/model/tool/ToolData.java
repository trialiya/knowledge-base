package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Протокольные данные tool-цикла, хранящиеся в колонке {@code chat_message.tool_data}. Ровно то,
 * что нужно для восстановления сообщения в формате OpenAI: у ASSISTANT-сообщения — список
 * tool_calls, у TOOL-сообщения — список ответов инструментов. В отличие от {@link
 * ToolInvocationMeta} (усечённые «крошки» для UI) здесь полный результат — модель должна видеть его
 * целиком на следующих итерациях цикла.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolData(@Nullable List<Call> toolCalls, @Nullable List<Response> responses) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Call(String id, String type, String name, String arguments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String id, String name, String responseData) {}

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
                        .map(
                                c ->
                                        new AssistantMessage.ToolCall(
                                                c.id(), c.type(), c.name(), c.arguments()))
                        .toList();
    }

    public List<ToolResponseMessage.ToolResponse> toToolResponses() {
        return responses == null
                ? List.of()
                : responses.stream()
                        .map(
                                r ->
                                        new ToolResponseMessage.ToolResponse(
                                                r.id(), r.name(), r.responseData()))
                        .toList();
    }
}
