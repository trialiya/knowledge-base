package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.Compact.truncateObject;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.ERROR;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Wraps a ToolCallback to record name, arguments and status into the request-scoped collector. */
public class RecordingToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final ThreadLocal<String> GIST = new ThreadLocal<>();

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
        try {
            return delegate.call(toolInput);
        } finally {
            GIST.remove();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        final String name = delegate.getToolDefinition().name();
        final ToolInvocationCollector collector = collectorFrom(toolContext);
        final Map<Object, Object> toolInputMap = parseToolInput(toolInput);
        if (collector != null) {
            collector.record(new ToolInvocation(name, toolInputMap, STARTED, null, null));
        }
        try {
            GIST.remove();
            String result = delegate.call(toolInput, toolContext);
            if (collector != null) {
                collector.record(new ToolInvocation(name, toolInputMap, OK, null, GIST.get()));
            }
            return result;
        } catch (Exception e) {
            if (collector != null) {
                collector.record(
                        new ToolInvocation(name, toolInputMap, ERROR, e.getMessage(), null));
            }
            throw e;
        } finally {
            GIST.remove();
        }
    }

    private static ToolInvocationCollector collectorFrom(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ToolInvocationCollector.KEY);
        return (value instanceof ToolInvocationCollector c) ? c : null;
    }

    private Map<Object, Object> parseToolInput(@Nullable String toolInput) {
        if (StringUtils.isBlank(toolInput)) {
            return Map.of();
        }
        try {
            return Optional.of((Map<Object, Object>) OBJECT_MAPPER.readValue(toolInput, Map.class))
                    .stream()
                    .map(Map::entrySet)
                    .flatMap(Collection::stream)
                    .filter(entry -> entry.getValue() != null)
                    .collect(
                            toMap(
                                    Map.Entry::getKey,
                                    entity -> truncateObject(entity.getValue(), 100)));
        } catch (NullPointerException | JsonProcessingException e) {
            logger.error("Error parsing tool input {}", toolInput, e);
            return Map.of();
        }
    }
}
