package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.Compact.truncate;
import static io.github.trialiya.kb.tools.Compact.truncateObject;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.ERROR;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.micrometer.common.util.StringUtils;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Wraps a ToolCallback to record name, arguments and status into the request-scoped collector. */
@Slf4j
public class RecordingToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final ThreadLocal<Object> CURRENT_RESULT = new ThreadLocal<>();

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
            CURRENT_RESULT.remove();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        final String name = delegate.getToolDefinition().name();
        final ToolInvocationCollector collector = collectorFrom(toolContext);
        final Map<Object, Object> toolInputMap = parseToolInput(toolInput);
        final int callIdx = collector != null ? collector.nextCallIndex() : -1;
        if (collector != null) {
            collector.record(
                    new ToolInvocation(
                            name, toolInputMap, STARTED, null, null, null, null, null, callIdx));
        }
        try {
            CURRENT_RESULT.remove();
            String result = delegate.call(toolInput, toolContext);
            if (collector != null) {
                collector.record(
                        new ToolInvocation(
                                name,
                                toolInputMap,
                                OK,
                                null,
                                getMeta(CURRENT_RESULT.get()),
                                getGist(CURRENT_RESULT.get()),
                                toolInput,
                                result,
                                callIdx));
            }
            return result;
        } catch (Exception e) {
            if (collector != null) {
                collector.record(
                        new ToolInvocation(
                                name,
                                toolInputMap,
                                ERROR,
                                e.getMessage(),
                                null,
                                null,
                                toolInput,
                                null,
                                callIdx));
            }
            throw e;
        } finally {
            CURRENT_RESULT.remove();
        }
    }

    private static ToolInvocationCollector collectorFrom(ToolContext toolContext) {
        return ToolInvocationCollector.from(toolContext);
    }

    /** JSON-аргументы вызова → map с усечёнными значениями (для UI-меты плашек). */
    public static Map<Object, Object> parseToolInput(@Nullable String toolInput) {
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
            log.error("Error parsing tool input {}", toolInput, e);
            return Map.of();
        }
    }

    private Map<String, ?> getMeta(Object result) {
        if (result instanceof ToolCallResultMetaProvider item) {
            return item.getResultMeta();
        } else if (result instanceof Collection<?> col
                && !col.isEmpty()
                && col.stream().allMatch(ToolCallResultMetaProvider.class::isInstance)) {
            return Map.of(
                    "items",
                    col.stream()
                            .map(ToolCallResultMetaProvider.class::cast)
                            .map(ToolCallResultMetaProvider::getResultMeta)
                            .toList());
        } else if (result instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(it -> it.getValue() instanceof ToolCallResultMetaProvider)
                    .collect(
                            toMap(
                                    entry -> entry.getKey().toString(),
                                    it ->
                                            ((ToolCallResultMetaProvider) it.getValue())
                                                    .getResultMeta()));
        }
        return Map.of();
    }

    private String getGist(Object result) {
        if (result instanceof ToolCallResponseItem item) {
            return item.getFormattedResponse();
        } else if (result instanceof Collection<?> col
                && !col.isEmpty()
                && col.stream().allMatch(ToolCallResponseItem.class::isInstance)) {
            String head = "size=" + col.size() + (col.size() > 5 ? " (first 5)" : "");
            String body =
                    col.stream()
                            .limit(5)
                            .map(e -> ((ToolCallResponseItem) e).getFormattedResponse())
                            .collect(Collectors.joining("\n"));
            return head + "\n" + body;
        } else if (result instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(it -> it.getValue() != null)
                    .collect(
                            toMap(
                                    Map.Entry::getKey,
                                    it -> {
                                        if (it.getValue() instanceof ToolCallResponseItem item) {
                                            return item.getFormattedResponse();
                                        }
                                        return truncateObject(it.getValue(), 30);
                                    }))
                    .toString();
        } else if (result instanceof String str) {
            return truncate(str, 50);
        }
        return truncateObject(result, 50);
    }
}
