package io.github.trialiya.kb.model.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/**
 * Pins how {@link SearchAgentResult} is split between the two consumers: the model payload (JSON
 * from the tool-result converter) versus the request-scoped invocation log ({@code getResultMeta} /
 * {@code getFormattedResponse}). {@code durationMs}, {@code model} и {@code usage} — операционные:
 * они нужны человеку в деталях вызова, но модели их отдавать незачем — увидев в результате
 * инструмента цену собственного поиска, она начнёт про неё рассуждать.
 */
class SearchAgentResultTest {

    private static final RunTokenUsage USAGE =
            new RunTokenUsage(18_400, 5_310, 870, 41_260, 29_800, 1_180, 4);

    @Test
    void modelPayloadExposesReportButHidesCostAndDuration() {
        SearchAgentResult result =
                new SearchAgentResult(
                        "billing", "найдено в Foo.java:10", true, 2, 1234L, "gpt-5-mini", USAGE);

        String json = new DefaultToolCallResultConverter().convert(result, SearchAgentResult.class);

        assertThat(json)
                .contains("report")
                .contains("найдено в Foo.java:10")
                .contains("complete")
                .contains("iterations")
                // The repository the citations are rooted in travels with them, always.
                .contains("\"project\":\"billing\"")
                .doesNotContain("durationMs")
                .doesNotContain("gpt-5-mini")
                .doesNotContain("contextTokens")
                .doesNotContain("usage");
    }

    @Test
    void invocationLogCarriesMetaAndGist() {
        SearchAgentResult result =
                new SearchAgentResult(
                        "billing", "a".repeat(300), false, 3, 1234L, "gpt-5-mini", USAGE);

        assertThat(result.getResultMeta())
                .containsEntry("project", "billing")
                .containsEntry("complete", false)
                .containsEntry("iterations", 3)
                .containsEntry("durationMs", 1234L)
                .containsEntry("reportChars", 300)
                .containsEntry("model", "gpt-5-mini")
                .containsEntry("usage", USAGE);

        // Incomplete runs are flagged, the gist is truncated, and the step count is shown.
        assertThat(result.getFormattedResponse()).contains("неполно").contains("3 шаг(ов)");
        assertThat(result.getFormattedResponse().length()).isLessThan(300);
    }

    /**
     * Эндпоинт суб-агента может usage не отдавать вовсе. Ключа тогда в мете нет — и это «не
     * измерено», а не насчитанный ноль, который фронт показал бы как бесплатный поиск.
     */
    @Test
    void anUnmeasuredRunCarriesNoUsageKey() {
        SearchAgentResult result =
                new SearchAgentResult(
                        "billing", "отчёт", true, 1, 10L, "gpt-5-mini", RunTokenUsage.EMPTY);

        assertThat(result.getResultMeta()).containsEntry("model", "gpt-5-mini");
        assertThat(result.getResultMeta()).doesNotContainKey("usage");
    }
}
