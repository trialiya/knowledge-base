package io.github.trialiya.kb.tools;

import io.github.trialiya.kb.model.tool.ModelView;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

public class CompactToolResultConverter implements ToolCallResultConverter {

    private static final DefaultToolCallResultConverter FALLBACK = new DefaultToolCallResultConverter();

    /**
     * A {@link ModelView} result is answered with its view; the whole result, serialised the same
     * way, goes to {@link RecordingToolCallback} for the call's detail view — only when the two
     * differ, so an untrimmed result is not kept twice.
     */
    @Override
    public String convert(@Nullable Object result, @Nullable Type returnType) {
        RecordingToolCallback.CURRENT_RESULT.set(result);
        if (result instanceof ModelView view) {
            String shown = FALLBACK.convert(view.forModel(), null);
            String full = FALLBACK.convert(result, returnType);
            if (!full.equals(shown)) {
                RecordingToolCallback.CURRENT_FULL_TEXT.set(full);
            }
            return shown;
        }
        return FALLBACK.convert(result, returnType);
    }
}
