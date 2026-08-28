package io.github.trialiya.kb.model.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ProjectScoped;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.tools.Compact;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Result of one {@code searchCodebase} sub-agent run.
 *
 * <p>The model consumes {@code report} (the findings text), {@code complete} (whether the search
 * finished on its own vs. was cut short by the iteration cap or an error) and {@code iterations}.
 * {@code durationMs}, {@code model} and {@code usage} are operational only — hidden from the model
 * payload but surfaced, together with the others, in the request-scoped invocation log via {@link
 * ToolCallResultMetaProvider} / {@link ToolCallResponseItem} (see {@code RecordingToolCallback}).
 *
 * <p>Скрыты они не для экономии: цена и модель собственного поиска — это то, о чём ассистенту
 * незачем рассуждать в ответе пользователю, а увидев их в результате инструмента, он начнёт. Числа
 * нужны человеку, и человек их видит — в деталях вызова.
 *
 * @param project id of the repository the sub-run searched — mandatory in the response, because
 *     {@code searchCodebase} can be pointed at a repository other than the chat's active one (see
 *     {@code SearchAgentFunction}), and the report's {@code path:line} citations mean nothing
 *     without knowing which repository they are rooted in
 * @param report the compact findings report (citations as {@code path:line})
 * @param complete {@code true} if the sub-agent produced the report on its own; {@code false} when
 *     the iteration budget was exhausted or a degraded path forced an early summary
 * @param iterations number of tool-call rounds the sub-agent executed
 * @param durationMs wall-clock time of the whole run, in milliseconds
 * @param model id модели, которой работал суб-агент ({@code kb.search.subagent.model-id}). Своя, не
 *     модель чата: в итог прогона эти токены не попадают и тарифицируются отдельно
 * @param usage токены суб-агента, посчитанные тем же правилом, что и токены прогона чата (см.
 *     {@link RunTokenUsage}); {@code null} — эндпоинт суб-агента usage не отдаёт
 */
public record SearchAgentResult(
        String project,
        @Nullable String report,
        boolean complete,
        int iterations,
        @JsonIgnore long durationMs,
        @JsonIgnore String model,
        @JsonIgnore @Nullable RunTokenUsage usage)
        implements ProjectScoped, ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Short, human-readable gist for the invocation log. */
    @Override
    public String getFormattedResponse() {
        String head = (complete ? "" : "⚠ неполно • ") + project + " • " + iterations + " шаг(ов)";
        return head + " • " + Compact.truncate(report, 160);
    }

    /**
     * Незаполненный usage в мету не кладём: {@code Map.of} не принимает {@code null}, а «ключа нет»
     * и значит «не измерено» — ровно то же, чем {@code null} был бы. Поэтому карта собирается
     * изменяемой и лишний ключ в неё просто не попадает.
     */
    @Override
    public Map<String, Object> getResultMeta() {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("project", project);
        meta.put("complete", complete);
        meta.put("iterations", iterations);
        meta.put("durationMs", durationMs);
        meta.put("reportChars", report == null ? 0 : report.length());
        meta.put("model", model);
        if (usage != null && !usage.isEmpty()) {
            meta.put("usage", usage);
        }
        return meta;
    }
}
