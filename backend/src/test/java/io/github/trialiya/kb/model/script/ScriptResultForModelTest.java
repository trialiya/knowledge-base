package io.github.trialiya.kb.model.script;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

/**
 * What the model is answered with for a script run ({@link ScriptResult#forModel}): {@code
 * filesRead} always cut to a few paths, {@code value} cut only when the call asked for it.
 */
class ScriptResultForModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompactToolResultConverter CONVERTER = new CompactToolResultConverter();

    /** Serialises a result as it is — the whole result, the way the model view is serialised too. */
    private static final DefaultToolCallResultConverter WHOLE = new DefaultToolCallResultConverter();

    @Test
    void filesReadIsCutToTheFirstFivePathsWithTheRestCounted() throws Exception {
        List<String> paths =
                IntStream.range(0, 12).mapToObj(i -> "src/F" + i + ".java").toList();

        Map<String, Object> shown = shown(result("r1", 42, paths));

        assertThat(shown.get("filesRead")).isEqualTo(paths.subList(0, 5));
        assertThat(shown.get("filesReadMore")).isEqualTo(7);
        assertThat(shown.get("value")).isEqualTo(42);
        assertThat(shown).doesNotContainKey("truncated");
    }

    @Test
    void aShortRunIsAnsweredExactlyAsBefore() throws Exception {
        ScriptResult result = result("r1", Map.of("a", 1), List.of("a.md", "b.md"));

        String shown = CONVERTER.convert(result, ScriptResult.class);

        assertThat(MAPPER.readValue(shown, Map.class))
                .containsOnlyKeys("project", "resultId", "value", "log", "stats", "error", "filesRead", "edits");
        // The same text the whole result makes: nothing extra is kept for the detail view.
        assertThat(shown).isEqualTo(WHOLE.convert(result, null));
    }

    @Test
    void withoutResultLimitALongValueIsShownWhole() throws Exception {
        List<Integer> rows = IntStream.range(0, 300).boxed().toList();

        assertThat(shown(result("r1", rows, List.of())).get("value")).isEqualTo(rows);
    }

    @Test
    void anArrayIsCutToItsFirstItems() throws Exception {
        List<Integer> rows = IntStream.range(0, 300).boxed().toList();

        Map<String, Object> shown = shown(result("r3", rows, List.of()).withResultLimit(3));

        assertThat(shown.get("value")).isEqualTo(List.of(0, 1, 2));
        Map<?, ?> truncated = (Map<?, ?>) shown.get("truncated");
        assertThat(truncated.get("limit")).isEqualTo(3);
        assertThat(truncated.get("cut")).isEqualTo(Map.of("$", 300));
        assertThat((String) truncated.get("note")).contains("kb.result('r3')");
    }

    @Test
    void aStringIsCutToItsFirstLines() throws Exception {
        Map<String, Object> shown = shown(result("r1", "a\nb\nc\nd", List.of()).withResultLimit(2));

        assertThat(shown.get("value")).isEqualTo("a\nb");
        assertThat(((Map<?, ?>) shown.get("truncated")).get("cut")).isEqualTo(Map.of("$", 4));
    }

    @Test
    void insideAnObjectEachArrayAndStringIsCutAndTheShapeKept() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("hits", List.of("x", "y", "z"));
        nested.put("deeper", Map.of("rows", List.of(1, 2, 3)));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("total", 3);
        value.put("rows", List.of(1, 2, 3));
        value.put("text", "one\ntwo\nthree");
        value.put("nested", nested);

        Map<String, Object> shown = shown(result("r1", value, List.of()).withResultLimit(2));

        Map<?, ?> cutValue = (Map<?, ?>) shown.get("value");
        assertThat(cutValue.get("total")).isEqualTo(3);
        assertThat(cutValue.get("rows")).isEqualTo(List.of(1, 2));
        assertThat(cutValue.get("text")).isEqualTo("one\ntwo");
        Map<?, ?> cutNested = (Map<?, ?>) cutValue.get("nested");
        assertThat(cutNested.get("hits")).isEqualTo(List.of("x", "y"));
        // Two levels of objects are searched, not more.
        assertThat(cutNested.get("deeper")).isEqualTo(Map.of("rows", List.of(1, 2, 3)));
        assertThat(((Map<?, ?>) shown.get("truncated")).get("cut"))
                .isEqualTo(Map.of("$.rows", 3, "$.text", 3, "$.nested.hits", 3));
    }

    @Test
    void aValueWithinTheLimitCarriesNoTruncationNote() throws Exception {
        Map<String, Object> shown = shown(result("r1", List.of(1, 2), List.of()).withResultLimit(5));

        assertThat(shown.get("value")).isEqualTo(List.of(1, 2));
        assertThat(shown).doesNotContainKey("truncated");
    }

    @Test
    void aValueNobodyKeptSaysTheRestIsGone() throws Exception {
        Map<String, Object> shown =
                shown(result(null, List.of(1, 2, 3), List.of()).withResultLimit(1));

        assertThat((String) ((Map<?, ?>) shown.get("truncated")).get("note")).contains("not kept");
    }

    @Test
    void aNonPositiveLimitShowsTheValueWhole() throws Exception {
        List<Integer> rows = List.of(1, 2, 3);

        assertThat(shown(result("r1", rows, List.of()).withResultLimit(0)).get("value"))
                .isEqualTo(rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWholeResultStillSerialisesEveryPathAndNoLimit() throws Exception {
        List<String> paths = IntStream.range(0, 8).mapToObj(i -> "p" + i).toList();

        Map<String, Object> whole = MAPPER.readValue(
                WHOLE.convert(result("r1", List.of(1, 2, 3), paths).withResultLimit(1), null), Map.class);

        assertThat(whole.get("filesRead")).isEqualTo(paths);
        assertThat(whole.get("value")).isEqualTo(List.of(1, 2, 3));
        assertThat(whole).doesNotContainKeys("resultLimit", "truncated", "filesReadMore");
    }

    /** The model's text, parsed back. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shown(ScriptResult result) throws Exception {
        return MAPPER.readValue(CONVERTER.convert(result, ScriptResult.class), Map.class);
    }

    private static ScriptResult result(@Nullable String resultId, @Nullable Object value, List<String> filesRead) {
        return new ScriptResult(
                "kb",
                resultId,
                null,
                value,
                List.of(),
                new ScriptStats(filesRead.size(), 0, 0, 0, 1),
                null,
                filesRead,
                List.of());
    }
}
