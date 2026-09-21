package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptParam;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a project's script manifest — the YAML in which a repository declares which of its files
 * {@code runSavedScript} may run.
 *
 * <pre>
 * scripts:
 *   - name: locale-diff
 *     file: frontend/scripts/locale-diff.js
 *     desc: Keys present in en and missing in ru, and the other way round
 *     params:
 *       - { name: area, desc: 'Limit to a subtree' }
 *   - name: bump-copyright
 *     file: scripts/bump-copyright.js
 *     desc: Put this year in the header of every changed file
 *     write: true
 *     timeout: 25s
 * </pre>
 *
 * <p><b>A bad entry costs that entry, not the manifest.</b> One misspelled field, one name used
 * twice, one missing description — each is dropped with a line in the log while its nineteen
 * neighbours keep working. The opposite (refuse the file) would let a one-character typo take away
 * every script the repository has, at the moment the model reaches for one.
 *
 * <p><b>An unknown field is an error for its entry</b> rather than something skipped quietly: a
 * {@code description:} where {@code desc:} was meant, or a {@code writes: true} that leaves the
 * script read-only, is exactly the mistake a silent parser turns into an afternoon.
 *
 * <p>Parsed with SnakeYAML's {@link SafeConstructor}: the manifest is repository content, and the
 * default constructor would let it name Java classes to instantiate.
 */
@Slf4j
final class ScriptManifestReader {

    /**
     * As for a project id and a skill name — and, since no colon fits, never {@code attachment:}.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private static final Set<String> SCRIPT_FIELDS =
            Set.of("name", "file", "desc", "params", "write", "timeout");

    private static final Set<String> PARAM_FIELDS =
            Set.of("name", "desc", "type", "required", "default");

    private ScriptManifestReader() {}

    /**
     * The scripts this manifest declares, in its own order.
     *
     * @param text the manifest as read from the working tree
     * @param where what the log lines call this file — the project and the path
     * @return every entry that parsed; empty when the file is empty, holds no {@code scripts:} list
     *     or is not YAML at all (all three are logged and none is fatal: the manifest is a file in
     *     somebody's branch, not this deployment's configuration)
     */
    static List<SavedScript> parse(String text, String where) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            log.warn("{}: not valid YAML — no saved scripts from it ({})", where, e.getMessage());
            return List.of();
        }
        Object scripts = root instanceof Map<?, ?> map ? map.get("scripts") : null;
        if (!(scripts instanceof List<?> entries)) {
            log.warn("{}: no `scripts:` list — nothing to run", where);
            return List.of();
        }
        List<SavedScript> resolved = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object entry : entries) {
            try {
                SavedScript script = script(entry);
                if (!names.add(script.name())) {
                    throw new IllegalArgumentException(
                            "name \"" + script.name() + "\" is used twice");
                }
                resolved.add(script);
            } catch (IllegalArgumentException e) {
                log.warn("{}: entry skipped — {}", where, e.getMessage());
            }
        }
        return List.copyOf(resolved);
    }

    private static SavedScript script(@Nullable Object entry) {
        Map<?, ?> fields = requireMap(entry, "a script entry");
        requireKnownFields(fields, SCRIPT_FIELDS, "script");
        String name = requireText(fields.get("name"), "name");
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name \""
                            + name
                            + "\" is not usable — lowercase letters, digits, '.', '_' and '-'"
                            + " only, starting with a letter or a digit");
        }
        return new SavedScript(
                name,
                requireText(fields.get("file"), "file"),
                requireText(fields.get("desc"), "desc"),
                params(fields.get("params"), name),
                bool(fields.get("write"), "write"),
                timeout(fields.get("timeout")));
    }

    private static List<ScriptParam> params(@Nullable Object raw, String script) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalArgumentException("\"" + script + "\": params must be a list");
        }
        List<ScriptParam> resolved = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object entry : entries) {
            ScriptParam param = param(entry, script);
            if (!names.add(param.name())) {
                throw new IllegalArgumentException(
                        "\"" + script + "\": param \"" + param.name() + "\" is declared twice");
            }
            resolved.add(param);
        }
        return List.copyOf(resolved);
    }

    private static ScriptParam param(@Nullable Object entry, String script) {
        Map<?, ?> fields = requireMap(entry, "\"" + script + "\": a params entry");
        requireKnownFields(fields, PARAM_FIELDS, "param of \"" + script + "\"");
        String name = requireText(fields.get("name"), "\"" + script + "\": param name");
        boolean required = bool(fields.get("required"), "required");
        Object fallback = fields.get("default");
        // Both at once is not a stricter declaration but a contradiction — the default could only
        // be reached through the refusal that `required` promises — so it is the entry's error.
        if (required && fallback != null) {
            throw new IllegalArgumentException(
                    "\""
                            + script
                            + "\": param \""
                            + name
                            + "\" is required and has a default — a required argument never falls"
                            + " back to one");
        }
        ScriptParam.Type type =
                fields.get("type") == null
                        ? ScriptParam.Type.STRING
                        : ScriptParam.Type.parse(String.valueOf(fields.get("type")));
        String desc = fields.get("desc") == null ? "" : String.valueOf(fields.get("desc")).strip();
        ScriptParam declared = new ScriptParam(name, desc, type, required, fallback);
        return fallback == null
                ? declared
                : new ScriptParam(
                        name,
                        desc,
                        type,
                        required,
                        ScriptArgs.checkDeclaredDefault(script, declared));
    }

    /**
     * Seconds is the unit of a bare number here, not Spring's default of milliseconds: every other
     * script budget in this project is spelled in seconds ({@code timeoutSeconds}, {@code
     * kb.script.timeout}), and {@code timeout: 30} silently meaning 30ms is a script that times out
     * on every call with nothing in the log to say why.
     */
    private static @Nullable Duration timeout(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        Duration timeout;
        try {
            timeout = DurationStyle.detectAndParse(String.valueOf(raw).strip(), ChronoUnit.SECONDS);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "timeout \"" + raw + "\" is not a duration (\"25s\", \"500ms\", \"30\")", e);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got \"" + raw + "\"");
        }
        return timeout;
    }

    private static Map<?, ?> requireMap(@Nullable Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(what + " must be a mapping");
    }

    private static void requireKnownFields(Map<?, ?> fields, Set<String> known, String what) {
        List<String> unknown =
                fields.keySet().stream()
                        .map(String::valueOf)
                        .filter(key -> !known.contains(key))
                        .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    what
                            + ": unknown field(s) "
                            + unknown
                            + " — known fields are "
                            + known.stream().sorted().toList());
        }
    }

    private static String requireText(@Nullable Object value, String field) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is missing or empty");
        }
        return text;
    }

    private static boolean bool(@Nullable Object value, String field) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(field + " must be true or false, got \"" + value + "\"");
    }
}
