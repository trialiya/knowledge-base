package io.github.trialiya.kb.service.chat.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a saved script is called with: the arguments checked against the script's declaration before
 * anything runs, and turned into the JSON the sandbox parses (see {@code ScriptRunner}).
 *
 * <p><b>Checked here rather than inside the script</b> because a refusal here costs nothing: no
 * context is built, no budget is spent, and the message names the argument the model has to fill
 * in. A script that validated its own arguments could only fail after the run had started, and
 * would have to be written twenty times over.
 *
 * <p><b>Two deliberate leniencies</b>, both aimed at the same failure. A model flagged {@code weak}
 * quotes everything, so {@code "50"} for a number and {@code "true"} for a boolean are converted
 * rather than refused — an extra round-trip buys nothing there. And an argument the script never
 * declared is <em>not</em> a refusal: it is passed through and noted in the run's log, where the
 * model reads it back as {@code unknown parameter "are"} and fixes the typo on its own.
 */
public final class ScriptArgs {

    /**
     * Cap on the serialized arguments. Arguments are parameters — a path, a limit, a date. Anything
     * that needs more than this is data, and data belongs in the repository the script reads.
     */
    static final int MAX_ARGS_CHARS = 16 * 1024;

    /**
     * The arguments cross into the sandbox as this text, which the guest's own JSON.parse reads.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptArgs() {}

    /**
     * The arguments one run was given.
     *
     * @param values what the script sees, after defaults and coercion — reported back in {@code
     *     ScriptRunSource} so the result says what actually ran, not what was typed
     * @param json the same values as ASCII JSON, ready to be evaluated in the sandbox
     * @param notes lines for the run's log: what was passed and not declared
     */
    public record Bound(Map<String, Object> values, String json, List<String> notes) {}

    /** No arguments at all — an inline {@code runScript}, whose constants are in its own text. */
    public static Bound none() {
        return new Bound(Map.of(), "{}", List.of());
    }

    /**
     * Binds a call's arguments to what the script declares.
     *
     * @throws IllegalArgumentException a required argument is missing, a value is of the wrong
     *     shape, or the arguments are too large — each message is the tool's answer to the model,
     *     so it names the script, the argument and what was expected
     */
    public static Bound bind(SavedScript script, @Nullable Map<String, Object> supplied) {
        Map<String, Object> given = supplied == null ? Map.of() : supplied;
        Map<String, Object> values = new LinkedHashMap<>();
        for (ScriptParam param : script.params()) {
            Object value = given.get(param.name());
            // Present-and-null is a value the caller chose — but never for a required argument,
            // where null is the absence the declaration exists to refuse.
            boolean present =
                    given.containsKey(param.name()) && !(value == null && param.required());
            if (present && value != null) {
                values.put(param.name(), coerce(script.name(), param, value));
            } else if (present) {
                values.put(param.name(), null);
            } else if (param.defaultValue() != null) {
                values.put(param.name(), param.defaultValue());
            } else if (param.required()) {
                throw new IllegalArgumentException(missing(script, param));
            }
        }
        List<String> notes = new ArrayList<>();
        given.forEach(
                (key, value) -> {
                    if (!values.containsKey(key)) {
                        values.put(key, value);
                        notes.add(
                                "[args] unknown parameter \""
                                        + key
                                        + "\" — passed through, but \""
                                        + script.name()
                                        + "\" declares "
                                        + declared(script));
                    }
                });
        return new Bound(values, json(script, values), List.copyOf(notes));
    }

    /**
     * The declared default, checked against its own declared type while the manifest is read — a
     * {@code default: "no"} on a boolean is the manifest's mistake, and finding it at the first
     * call instead would hand the model an error about a value it never passed.
     */
    static Object checkDeclaredDefault(String script, ScriptParam param) {
        return coerce(script, param, java.util.Objects.requireNonNull(param.defaultValue()));
    }

    private static Object coerce(String script, ScriptParam param, Object value) {
        return switch (param.type()) {
            case STRING ->
                    value instanceof List<?> || value instanceof Map<?, ?>
                            ? refuse(script, param, value, "a string")
                            : String.valueOf(value);
            case NUMBER -> number(script, param, value);
            case BOOLEAN -> bool(script, param, value);
            case ARRAY ->
                    value instanceof List<?> ? value : refuse(script, param, value, "an array");
            case OBJECT ->
                    value instanceof Map<?, ?> ? value : refuse(script, param, value, "an object");
        };
    }

    private static Object number(String script, ScriptParam param, Object value) {
        if (value instanceof Number number) {
            return number;
        }
        String text = String.valueOf(value).strip();
        try {
            // Not a ternary: a conditional with a Long and a Double branch is promoted to double,
            // so every whole number would arrive in the script as 50.0.
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return refuse(script, param, value, "a number");
        }
    }

    private static Object bool(String script, ScriptParam param, Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = String.valueOf(value).strip();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.valueOf(text);
        }
        return refuse(script, param, value, "true or false");
    }

    private static Object refuse(String script, ScriptParam param, Object value, String expected) {
        throw new IllegalArgumentException(
                "Script \""
                        + script
                        + "\": argument \""
                        + param.name()
                        + "\" must be "
                        + expected
                        + ", got "
                        + quote(value)
                        + ".");
    }

    private static String missing(SavedScript script, ScriptParam param) {
        return "Script \""
                + script.name()
                + "\" needs argument \""
                + param.name()
                + "\""
                + (param.desc().isEmpty() ? "" : " (" + param.desc() + ")")
                + ". "
                + declaredSentence(script);
    }

    private static String json(SavedScript script, Map<String, Object> values) {
        String json;
        try {
            json = MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\": the arguments are not representable as JSON — pass strings,"
                            + " numbers, booleans, arrays and objects only",
                    e);
        }
        if (json.length() > MAX_ARGS_CHARS) {
            throw new IllegalArgumentException(
                    "Script \""
                            + script.name()
                            + "\": the arguments are too large ("
                            + json.length()
                            + " chars, the limit is "
                            + MAX_ARGS_CHARS
                            + ") — pass a path or a query and let the script read the data itself");
        }
        return json;
    }

    /** The declaration, as the model has to read it back to fix a call. */
    private static String declared(SavedScript script) {
        if (script.params().isEmpty()) {
            return "no arguments";
        }
        return script.params().stream()
                .map(param -> param.name() + (param.required() ? "" : "?"))
                .toList()
                .toString();
    }

    private static String declaredSentence(SavedScript script) {
        return "It declares " + declared(script) + ".";
    }

    private static String quote(Object value) {
        String text = String.valueOf(value);
        return "\"" + (text.length() > 60 ? text.substring(0, 60) + "…" : text) + "\"";
    }
}
