package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nullable;
import java.util.Map;

public record ToolInvocationMeta(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        String error,
        Map<String, ?> resultMeta,
        /** null → информация недоступна (старые данные), фронт считает true */
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Boolean hasDetails) {}
