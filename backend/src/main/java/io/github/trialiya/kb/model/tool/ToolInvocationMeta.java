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
         * Протокольный id вызова ({@code tool_call.id} / {@code ToolData.Call#id}) — единственный
         * ключ, нужный модалке деталей: {@code GET /tool-calls} находит messageId/responseMessageId
         * сам, через {@code tool_call_index} (см. {@link
         * io.github.trialiya.kb.service.ChatMemoryService#findToolCallDetail}). null у старых
         * записей.
         */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String callId) {}
