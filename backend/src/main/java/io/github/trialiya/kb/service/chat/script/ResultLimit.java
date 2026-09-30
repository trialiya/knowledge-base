package io.github.trialiya.kb.service.chat.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Cuts a script's returned value down to the first {@code limit} elements for the model's copy —
 * the {@code resultLimit} argument of {@code runScript} / {@code runSavedScript}. Runs on the whole
 * parsed value, before {@code max-result-chars} cuts the text (see {@link ScriptResultKeeper}): a
 * character cut first would leave a fragment that is no longer JSON, and nothing left to count.
 *
 * <p>An element is an array item or a line of a string. Inside an object the arrays and strings it
 * holds are cut the same way, down to {@link #OBJECT_DEPTH} levels of nesting; deeper, and inside
 * array items, nothing is touched. The value keeps its shape and stays valid JSON. Lines of a cut
 * string are joined back with {@code \n}, whatever ended them ({@code \r\n} too).
 */
final class ResultLimit {

    /** How many levels of nested objects are searched for arrays and strings to cut. */
    private static final int OBJECT_DEPTH = 2;

    private static final String ROOT = "$";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private ResultLimit() {}

    /**
     * @param value the value with every cut part shortened
     * @param cut the JSON path of every part that was cut ({@code $}, {@code $.rows}) → how many
     *     elements it had; empty when nothing was longer than the limit
     */
    record Trimmed(@Nullable Object value, Map<String, Integer> cut) {}

    static Trimmed apply(@Nullable Object value, int limit) {
        Map<String, Integer> cut = new LinkedHashMap<>();
        return new Trimmed(trim(value, limit, ROOT, 0, cut), cut);
    }

    /** {@code $.rows} for a plain key, {@code $["a.b"]} for one a dotted path would misread. */
    private static String child(String path, Object key) {
        String name = String.valueOf(key);
        return IDENTIFIER.matcher(name).matches()
                ? path + "." + name
                : path + "[\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    private static @Nullable Object trim(
            @Nullable Object value, int limit, String path, int depth, Map<String, Integer> cut) {
        if (value instanceof List<?> list) {
            if (list.size() <= limit) {
                return list;
            }
            cut.put(path, list.size());
            // Not List.copyOf: a JSON array may hold nulls.
            return new ArrayList<>(list.subList(0, limit));
        }
        if (value instanceof String text) {
            List<String> lines = text.lines().toList();
            if (lines.size() <= limit) {
                return text;
            }
            cut.put(path, lines.size());
            return String.join("\n", lines.subList(0, limit));
        }
        if (value instanceof Map<?, ?> map && depth < OBJECT_DEPTH) {
            Map<Object, @Nullable Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(key, trim(item, limit, child(path, key), depth + 1, cut)));
            return out;
        }
        return value;
    }
}
