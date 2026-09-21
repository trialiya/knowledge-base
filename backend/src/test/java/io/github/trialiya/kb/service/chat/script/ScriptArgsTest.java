package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptParam;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Binding a call's arguments to what the script declared.
 *
 * <p>Two policies are pinned here rather than the mechanics. A wrong <em>shape</em> is refused
 * before the run, because the refusal costs nothing there and names the argument to fix; a wrong
 * <em>name</em> is not, because an unknown key harms nothing and the note in the log gets the model
 * to the same fix without a round-trip.
 */
class ScriptArgsTest {

    /**
     * U+2028, spelled without a unicode escape: Java resolves those before lexing, so an escape
     * here would put a raw line separator in this file — which javac accepts inside a string
     * literal and the formatter reads as an unclosed one.
     */
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);

    @Test
    void appliesDefaultsAndKeepsDeclaredOrder() {
        ScriptArgs.Bound bound =
                ScriptArgs.bind(
                        script(
                                param("area", ScriptParam.Type.STRING, false, null),
                                param("limit", ScriptParam.Type.NUMBER, false, 50)),
                        Map.of("area", "frontend/src"));

        assertThat(bound.values())
                .containsExactly(Map.entry("area", "frontend/src"), Map.entry("limit", 50));
        assertThat(bound.notes()).isEmpty();
    }

    /** A default fills a gap, never a value: an explicit null is what the caller chose to pass. */
    @Test
    void anExplicitNullIsAValueAndNotAGap() {
        Map<String, Object> given = new HashMap<>();
        given.put("area", null);

        ScriptArgs.Bound bound =
                ScriptArgs.bind(
                        script(param("area", ScriptParam.Type.STRING, false, "docs")), given);

        assertThat(bound.values()).hasSize(1).containsEntry("area", null);
        assertThat(bound.json()).isEqualTo("{\"area\":null}");
    }

    @Test
    void refusesAMissingRequiredArgumentNamingIt() {
        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(param("since", ScriptParam.Type.STRING, true, null)),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("since")
                .hasMessageContaining("declares");
    }

    /** A model flagged weak quotes everything; an extra round-trip over that buys nothing. */
    @Test
    void convertsAQuotedNumberOrBoolean() {
        ScriptArgs.Bound bound =
                ScriptArgs.bind(
                        script(
                                param("limit", ScriptParam.Type.NUMBER, false, null),
                                param("dry", ScriptParam.Type.BOOLEAN, false, null)),
                        Map.of("limit", "50", "dry", "TRUE"));

        assertThat(bound.values()).containsExactly(Map.entry("limit", 50L), Map.entry("dry", true));
    }

    @Test
    void refusesAValueThatIsNotTheDeclaredShape() {
        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(
                                                param(
                                                        "limit",
                                                        ScriptParam.Type.NUMBER,
                                                        false,
                                                        null)),
                                        Map.of("limit", "soon")))
                .hasMessageContaining("must be a number");
        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(param("paths", ScriptParam.Type.ARRAY, false, null)),
                                        Map.of("paths", "a.js")))
                .hasMessageContaining("must be an array");
        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(param("area", ScriptParam.Type.STRING, false, null)),
                                        Map.of("area", List.of("a", "b"))))
                .hasMessageContaining("must be a string");
    }

    @Test
    void passesAnUndeclaredArgumentThroughWithANote() {
        ScriptArgs.Bound bound =
                ScriptArgs.bind(
                        script(param("area", ScriptParam.Type.STRING, false, null)),
                        Map.of("are", "typo"));

        assertThat(bound.values()).containsEntry("are", "typo");
        assertThat(bound.notes())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("unknown parameter \"are\"")
                .contains("area");
    }

    /**
     * Explicit null is a value the caller chose — except where the declaration says the argument is
     * required, and null is precisely the absence it exists to refuse.
     */
    @Test
    void anExplicitNullDoesNotSatisfyARequiredArgument() {
        Map<String, Object> given = new HashMap<>();
        given.put("since", null);

        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(param("since", ScriptParam.Type.STRING, true, null)),
                                        given))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("since");
    }

    /** What the sandbox parses is this text, so it has to survive the trip unchanged. */
    @Test
    void serializesValuesAsJsonTheGuestCanParse() {
        ScriptArgs.Bound bound =
                ScriptArgs.bind(
                        script(param("area", ScriptParam.Type.STRING, false, null)),
                        Map.of("area", "док" + LINE_SEPARATOR + "и"));

        assertThat(bound.json()).isEqualTo("{\"area\":\"док" + LINE_SEPARATOR + "и\"}");
    }

    @Test
    void refusesArgumentsTooLargeToBeParameters() {
        assertThatThrownBy(
                        () ->
                                ScriptArgs.bind(
                                        script(param("blob", ScriptParam.Type.STRING, false, null)),
                                        Map.of("blob", "x".repeat(ScriptArgs.MAX_ARGS_CHARS + 1))))
                .hasMessageContaining("too large");
    }

    @Test
    void anInlineScriptRunsWithAnEmptyObject() {
        assertThat(ScriptArgs.none().json()).isEqualTo("{}");
        assertThat(ScriptArgs.none().values()).isEmpty();
    }

    private static SavedScript script(ScriptParam... params) {
        return new SavedScript("sample", "a.js", "A sample", List.of(params), false, null);
    }

    private static ScriptParam param(
            String name, ScriptParam.Type type, boolean required, @Nullable Object fallback) {
        return new ScriptParam(name, "", type, required, fallback);
    }
}
