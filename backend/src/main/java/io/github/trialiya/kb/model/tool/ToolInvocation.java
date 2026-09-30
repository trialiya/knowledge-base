package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Один вызов инструмента в рамках прогона.
 *
 * @param project канонический id репозитория, который ответил, — только у инструментов, работающих
 *     с репозиторием ({@link ProjectScoped}); {@code null} у всех остальных и означает «результат
 *     не привязан к репозиторию». По нему guard записи ({@code
 *     ToolInvocationCollector#hasSeenFile}) решает, в том ли репозитории модель видела файл. Живёт
 *     только в памяти прогона — в историю не сохраняется, там ответ и так несёт своё поле {@code
 *     project}
 * @param fullResultText JSON результата целиком, когда {@code resultText} — то, чем ответили модели,
 *     — его урезанный вид ({@link ModelView}); {@code null}, если модель получила результат
 *     целиком. В памяти прогона; в истории то же лежит в {@code ToolData.Response#fullData}
 */
public record ToolInvocation(
        String name,
        Map<Object, Object> arguments,
        ToolInvocationCollector.ToolInvocationStatus status,
        @Nullable String error,
        @JsonIgnore @Nullable Map<String, ?> resultMeta,
        @Nullable String resultGist,
        @JsonIgnore String argumentsRaw,
        @JsonIgnore @Nullable String resultText,
        int callIndex,
        @JsonIgnore @Nullable String project,
        @JsonIgnore @Nullable String fullResultText) {

    /** Вызов, результат которого модель получила целиком. */
    public ToolInvocation(
            String name,
            Map<Object, Object> arguments,
            ToolInvocationCollector.ToolInvocationStatus status,
            @Nullable String error,
            @Nullable Map<String, ?> resultMeta,
            @Nullable String resultGist,
            String argumentsRaw,
            @Nullable String resultText,
            int callIndex,
            @Nullable String project) {
        this(
                name,
                arguments,
                status,
                error,
                resultMeta,
                resultGist,
                argumentsRaw,
                resultText,
                callIndex,
                project,
                null);
    }

    /** Результат целиком — то, что показывает окно деталей и в чём ищет {@code hasSeenFile}. */
    @JsonIgnore
    public @Nullable String wholeResultText() {
        return fullResultText != null ? fullResultText : resultText;
    }

    @JsonIgnore
    public ToolInvocationMeta toMeta(boolean hasDetails) {
        return toMeta(hasDetails, null);
    }

    /**
     * @param callId протокольный id вызова, если он уже известен (сопоставлен позиционно с {@code
     *     tool_data.toolCalls} — {@code RecordingToolCallback} его не видит)
     */
    @JsonIgnore
    public ToolInvocationMeta toMeta(boolean hasDetails, @Nullable String callId) {
        return new ToolInvocationMeta(
                name, arguments, status, error, resultMeta, hasDetails, callIndex, resultGist, callId);
    }
}
