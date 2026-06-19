package io.github.trialiya.kb.model.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/**
 * Pins how {@link SearchAgentResult} is split between the two consumers: the model payload (JSON
 * from the tool-result converter) versus the request-scoped invocation log ({@code getResultMeta} /
 * {@code getFormattedResponse}). {@code durationMs} is operational only and must not reach the
 * model.
 */
class SearchAgentResultTest {

    @Test
    void modelPayloadExposesReportButHidesDuration() {
        SearchAgentResult result = new SearchAgentResult("найдено в Foo.java:10", true, 2, 1234L);

        String json = new DefaultToolCallResultConverter().convert(result, SearchAgentResult.class);

        assertThat(json)
                .contains("report")
                .contains("найдено в Foo.java:10")
                .contains("complete")
                .contains("iterations")
                .doesNotContain("durationMs");
    }

    @Test
    void invocationLogCarriesMetaAndGist() {
        SearchAgentResult result = new SearchAgentResult("a".repeat(300), false, 3, 1234L);

        assertThat(result.getResultMeta())
                .containsEntry("complete", false)
                .containsEntry("iterations", 3)
                .containsEntry("durationMs", 1234L)
                .containsEntry("reportChars", 300);

        // Incomplete runs are flagged, the gist is truncated, and the step count is shown.
        assertThat(result.getFormattedResponse()).contains("неполно").contains("3 шаг(ов)");
        assertThat(result.getFormattedResponse().length()).isLessThan(300);
    }
}
