package io.github.trialiya.kb.tools;

import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

public class CompactToolResultConverter implements ToolCallResultConverter {

    private static final DefaultToolCallResultConverter FALLBACK =
            new DefaultToolCallResultConverter();

    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        String convert = FALLBACK.convert(result, returnType);
        RecordingToolCallback.CURRENT_RESULT.set(result);
        return convert;
    }
}
