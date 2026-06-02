package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.Compact.truncate;
import static io.github.trialiya.kb.tools.Compact.truncateObject;
import static java.util.stream.Collectors.toMap;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

public class CompactToolResultConverter implements ToolCallResultConverter {

    private static final DefaultToolCallResultConverter FALLBACK =
            new DefaultToolCallResultConverter();

    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        String convert = FALLBACK.convert(result, returnType);
        RecordingToolCallback.GIST.set(getGist(result, convert));
        return convert;
    }

    private String getGist(@Nullable Object result, @Nullable String convert) {
        if (result instanceof ToolCallResponseItem item) {
            return item.getFormattedResponse();
        } else if (result instanceof Collection<?> col
                && !col.isEmpty()
                && col.stream().allMatch(ToolCallResponseItem.class::isInstance)) {
            String head = "size=" + col.size() + (col.size() > 5 ? " (first 5)" : "");
            String body =
                    col.stream()
                            .limit(5) // ← до map
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
        }
        return truncate(convert, 50);
    }
}
