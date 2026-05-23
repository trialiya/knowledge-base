package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.ERROR;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocation;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Wraps a ToolCallback to record name, arguments and status into the request-scoped collector. */
public class RecordingToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;

    public RecordingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    // Called when there is no ToolContext — no collector available, just delegate.
    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        final String name = delegate.getToolDefinition().name();
        final ToolInvocationCollector collector = collectorFrom(toolContext);
        final Map<Object, Object> toolInputMap = parseToolInput(toolInput);
        if (collector != null) {
            collector.record(new ToolInvocation(name, toolInputMap, STARTED, null));
        }
        try {
            String result = delegate.call(toolInput, toolContext);
            if (collector != null) {
                collector.record(new ToolInvocation(name, toolInputMap, OK, null));
            }
            return result;
        } catch (Exception e) {
            if (collector != null) {
                collector.record(new ToolInvocation(name, toolInputMap, ERROR, e.getMessage()));
            }
            throw e;
        }
    }

    private static ToolInvocationCollector collectorFrom(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ToolInvocationCollector.KEY);
        return (value instanceof ToolInvocationCollector c) ? c : null;
    }

    private static final Set<String> ALLOWED_INPUT_ARGUMENTS =
            Set.of(
                    "topic",
                    "filePath",
                    "pattern",
                    "commitHashes",
                    "path",
                    "name",
                    "id",
                    "title",
                    "parentId",
                    "documentId");

    private Map<Object, Object> parseToolInput(@Nullable String toolInput) {
        if (StringUtils.isBlank(toolInput)) {
            return Map.of();
        }
        try {
            return Optional.of((Map<Object, Object>) OBJECT_MAPPER.readValue(toolInput, Map.class))
                    .stream()
                    .map(Map::entrySet)
                    .flatMap(Collection::stream)
                    .filter(entry -> ALLOWED_INPUT_ARGUMENTS.contains(entry.getKey().toString()))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        } catch (JsonProcessingException e) {
            logger.error("Error parsing tool input {}", toolInput, e);
            return Map.of();
        }
    }
}
