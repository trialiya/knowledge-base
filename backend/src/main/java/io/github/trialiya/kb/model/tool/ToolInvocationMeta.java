package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ToolInvocationMeta(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        @Nullable String error,
        @Nullable Map<String, ?> resultMeta,
        /** null → информация недоступна (старые данные), фронт считает true */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Boolean hasDetails,
        /** null → старые данные без индекса */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer callIndex,
        /** Короткое превью результата для плашки; null у старых записей. */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String resultGist,
        /**
         * id ASSISTANT-сообщения ({@code chat_message.id}), сохранившего этот вызов в {@code
         * tool_data.toolCalls} — вместе с {@link #callId} и {@link #responseMessageId} позволяет
         * достать полные детали вызова (сырые аргументы, полный результат) точечным {@code
         * findAllById} по двум строкам, без сканирования истории чата. null у старых записей.
         */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long messageId,
        /**
         * Протокольный id вызова ({@code tool_call.id} / {@code ToolData.Call#id}); null у старых
         * записей.
         */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String callId,
        /**
         * id TOOL-сообщения с ответом на этот вызов; null пока результата нет или у старых записей.
         */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long responseMessageId) {}
