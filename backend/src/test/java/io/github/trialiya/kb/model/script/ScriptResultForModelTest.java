package io.github.trialiya.kb.model.script;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/**
 * What the model is answered with for a script run ({@link ScriptResult#forModel}): {@code
 * filesRead} always cut to a few paths, {@code value} replaced only where the call's {@code
 * resultLimit} cut it (the cutting itself is {@code ResultLimitTest}'s and {@code
 * ScriptResultSharingTest}'s).
 */
class ScriptResultForModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompactToolResultConverter CONVERTER = new CompactToolResultConverter();

    /** Serialises a result as it is — the whole result, the way the model view is serialised too. */
    private static final DefaultToolCallResultConverter WHOLE = new DefaultToolCallResultConverter();

    @Test
    void filesReadIsCutToTheFirstPathsWithTheRestCounted() throws Exception {
        List<String> paths =
                IntStream.range(0, 12).mapToObj(i -> "src/F" + i + ".java").toList();

        Map<String, Object> shown = shown(result("r1", 42, paths, null));

        assertThat(shown.get("filesRead")).isEqualTo(paths.subList(0, ScriptResult.MODEL_PATH_LIMIT));
        assertThat(shown.get("filesReadMore")).isEqualTo(12 - ScriptResult.MODEL_PATH_LIMIT);
        assertThat(shown.get("value")).isEqualTo(42);
        assertThat(shown).doesNotContainKey("truncated");
    }

    @Test
    void aShortRunIsAnsweredExactlyAsBefore() throws Exception {
        ScriptResult result = result("r1", Map.of("a", 1), List.of("a.md", "b.md"), null);

        String shown = CONVERTER.convert(result, ScriptResult.class);

        assertThat(MAPPER.readValue(shown, Map.class))
                .containsOnlyKeys("project", "resultId", "value", "log", "stats", "error", "filesRead", "edits");
        // The same text the whole result makes: nothing extra is kept for the detail view.
        assertThat(shown).isEqualTo(WHOLE.convert(result, null));
    }

    @Test
    void aValueTheLimitCutIsShownInPlaceOfTheWholeOne() throws Exception {
        ScriptResult.Truncated truncated = ScriptResult.Truncated.of(3, Map.of("$", 300), "r3");
        List<Integer> rows = IntStream.range(0, 300).boxed().toList();

        Map<String, Object> shown =
                shown(result("r3", rows, List.of(), new ScriptResult.Shown(List.of(0, 1, 2), truncated)));

        assertThat(shown.get("value")).isEqualTo(List.of(0, 1, 2));
        Map<?, ?> note = (Map<?, ?>) shown.get("truncated");
        assertThat(note.get("limit")).isEqualTo(3);
        assertThat(note.get("cut")).isEqualTo(Map.of("$", 300));
        assertThat((String) note.get("note")).contains("kb.result('r3')");
    }

    @Test
    void aValueNobodyKeptSaysTheRestIsGone() {
        assertThat(ScriptResult.Truncated.of(1, Map.of("$", 3), null).note()).contains("not kept");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWholeResultStillSerialisesEveryPathAndTheWholeValue() throws Exception {
        List<String> paths = IntStream.range(0, 8).mapToObj(i -> "p" + i).toList();
        ScriptResult.Shown cut = new ScriptResult.Shown(List.of(1), ScriptResult.Truncated.of(1, Map.of("$", 3), "r1"));

        Map<String, Object> whole =
                MAPPER.readValue(WHOLE.convert(result("r1", List.of(1, 2, 3), paths, cut), null), Map.class);

        assertThat(whole.get("filesRead")).isEqualTo(paths);
        assertThat(whole.get("value")).isEqualTo(List.of(1, 2, 3));
        assertThat(whole).doesNotContainKeys("shown", "truncated", "filesReadMore");
    }

    @Test
    void aNoteOnTheFullCopysCutReachesOnlyTheFullLog() {
        ScriptResult.Shown cut = new ScriptResult.Shown(
                List.of(1), ScriptResult.Truncated.of(1, Map.of("$", 3), "r1"), "Full response truncated");

        ScriptResult result = result("r1", "[1,", List.of(), cut);

        assertThat(result.log()).containsExactly("Full response truncated");
        assertThat(result.forModel().log()).isEmpty();
    }

    /** The model's text, parsed back. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shown(ScriptResult result) throws Exception {
        return MAPPER.readValue(CONVERTER.convert(result, ScriptResult.class), Map.class);
    }

    private static ScriptResult result(
            @Nullable String resultId,
            @Nullable Object value,
            List<String> filesRead,
            ScriptResult.@Nullable Shown shown) {
        return new ScriptResult(
                "kb",
                resultId,
                null,
                value,
                List.of(),
                new ScriptStats(filesRead.size(), 0, 0, 0, 1),
                null,
                filesRead,
                List.of(),
                shown);
    }
}
