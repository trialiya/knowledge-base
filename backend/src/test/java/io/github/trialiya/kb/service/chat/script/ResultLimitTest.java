package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Cutting a parsed value to its first elements ({@link ResultLimit}), shape kept. */
class ResultLimitTest {

    @Test
    void anArrayIsCutToItsFirstItems() {
        ResultLimit.Trimmed trimmed =
                ResultLimit.apply(IntStream.range(0, 300).boxed().toList(), 3);

        assertThat(trimmed.value()).isEqualTo(List.of(0, 1, 2));
        assertThat(trimmed.cut()).isEqualTo(Map.of("$", 300));
    }

    @Test
    void aStringIsCutToItsFirstLines() {
        ResultLimit.Trimmed trimmed = ResultLimit.apply("a\nb\nc\nd", 2);

        assertThat(trimmed.value()).isEqualTo("a\nb");
        assertThat(trimmed.cut()).isEqualTo(Map.of("$", 4));
    }

    @Test
    void insideAnObjectEachArrayAndStringIsCutAndTheShapeKept() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("hits", List.of("x", "y", "z"));
        nested.put("deeper", Map.of("rows", List.of(1, 2, 3)));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("total", 3);
        value.put("rows", List.of(1, 2, 3));
        value.put("text", "one\ntwo\nthree");
        value.put("nested", nested);

        ResultLimit.Trimmed trimmed = ResultLimit.apply(value, 2);

        Map<?, ?> cut = (Map<?, ?>) trimmed.value();
        assertThat(cut.get("total")).isEqualTo(3);
        assertThat(cut.get("rows")).isEqualTo(List.of(1, 2));
        assertThat(cut.get("text")).isEqualTo("one\ntwo");
        Map<?, ?> cutNested = (Map<?, ?>) cut.get("nested");
        assertThat(cutNested.get("hits")).isEqualTo(List.of("x", "y"));
        // Two levels of objects are searched, not more.
        assertThat(cutNested.get("deeper")).isEqualTo(Map.of("rows", List.of(1, 2, 3)));
        assertThat(trimmed.cut()).isEqualTo(Map.of("$.rows", 3, "$.text", 3, "$.nested.hits", 3));
    }

    @Test
    void itemsInsideACutArrayAreLeftWhole() {
        ResultLimit.Trimmed trimmed = ResultLimit.apply(List.of(List.of(1, 2, 3), List.of(4), List.of(5)), 2);

        assertThat(trimmed.value()).isEqualTo(List.of(List.of(1, 2, 3), List.of(4)));
        assertThat(trimmed.cut()).isEqualTo(Map.of("$", 3));
    }

    @Test
    void aKeyADottedPathWouldMisreadIsQuoted() {
        ResultLimit.Trimmed trimmed = ResultLimit.apply(Map.of("a.b", List.of(1, 2, 3)), 1);

        assertThat(trimmed.cut()).isEqualTo(Map.of("$[\"a.b\"]", 3));
        assertThat(ResultLimit.apply(Map.of("a\"b\\c", List.of(1, 2)), 1).cut())
                .isEqualTo(Map.of("$[\"a\\\"b\\\\c\"]", 2));
    }

    @Test
    void aValueWithinTheLimitIsNotCut() {
        assertThat(ResultLimit.apply(List.of(1, 2), 5).cut()).isEmpty();
        assertThat(ResultLimit.apply(null, 5).cut()).isEmpty();
        assertThat(ResultLimit.apply(42, 1).cut()).isEmpty();
    }
}
