package io.github.trialiya.kb.tools;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code @Tool} method does with an argument the model left out.
 *
 * <p>Weak models routinely omit arguments the schema marks as required. Spring AI does not defend
 * against that: {@code MethodToolCallback} looks the name up in the argument map, gets {@code
 * null}, and passes it straight to {@code Method.invoke}. For a wrapper type that is a {@code null}
 * reference the tool body can inspect; for a <b>primitive</b> parameter reflection cannot unbox it
 * and throws {@code IllegalArgumentException} — and because that is thrown by {@code invoke} itself
 * rather than by the tool, it is not wrapped in {@code ToolExecutionException} and never reaches
 * the exception processor. One missing {@code boolean} takes down the whole chat run.
 *
 * <p>Hence the two halves of the policy, which every tool in {@code functions} follows:
 *
 * <ul>
 *   <li><b>No primitives in a {@code @Tool} signature.</b> Declare {@code Long}/{@code
 *       Integer}/{@code Boolean} so a gap arrives as {@code null} and can be answered.
 *   <li><b>Answer the gap on purpose</b> — either a default that is genuinely what the caller meant
 *       ({@link #orDefault}), or an error naming the argument ({@code require*}). The error travels
 *       back to the model as the tool result, so it reads which argument to fill in and retries;
 *       silently substituting a value would instead have it act on a result it did not ask for.
 * </ul>
 *
 * <p>Defaulting is for arguments the tool can do without ({@code required = false} in the schema:
 * limits, modes, flags). Anything the call is <em>about</em> — an id, a path, a query, the content
 * being written — is required, because there is no value that could stand in for it.
 */
public final class ToolArgs {

    private ToolArgs() {}

    // ── Required: no default could stand in ───────────────────────────────────

    /**
     * Text that names something (id, path, query, title). Whitespace-only counts as missing — a
     * blank name is never a real one.
     */
    public static String requireText(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw missing(name);
        }
        return value;
    }

    /**
     * Text that <em>is</em> the payload (file content, section body, replacement text). Only an
     * omitted argument is an error: an explicit empty string is a real instruction — create an
     * empty file, delete the fragment — and must not be confused with a gap.
     */
    public static String requireContent(@Nullable String value, String name) {
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /** Any non-text argument that has no sensible default (enum, record, wrapper). */
    public static <T> T requireValue(@Nullable T value, String name) {
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    /**
     * A list argument the tool has nothing to do without: absent and empty are the same failure.
     */
    public static <T> List<T> requireNonEmpty(@Nullable List<T> value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool argument '"
                            + name
                            + "' is empty. Call the tool again with at least one value in it.");
        }
        return value;
    }

    /**
     * A numeric id, whichever way the model spelled it — {@code 42}, {@code "42"} or {@code " 42
     * "}. Anything else fails here with the offending text quoted, instead of reaching the caller
     * as a bare {@code NumberFormatException} whose message ("For input string: ...") does not say
     * which argument was wrong.
     */
    public static long requireId(@Nullable Object value, String name) {
        if (value == null) {
            throw missing(name);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        final String text = value.toString().strip();
        if (text.isEmpty()) {
            throw missing(name);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Tool argument '"
                            + name
                            + "' must be a single numeric id, got \""
                            + (text.length() > 60 ? text.substring(0, 60) + "…" : text)
                            + "\". Call the tool again with one id.");
        }
    }

    /** A required whole number that is not an id (a version to check against, a count). */
    public static int requireInt(@Nullable Integer value, String name) {
        if (value == null) {
            throw missing(name);
        }
        return value;
    }

    // ── Optional: the default is what the caller meant ────────────────────────

    /** Blank counts as unset: a mode or scope the model left empty is one it did not choose. */
    public static String orDefault(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static boolean orDefault(@Nullable Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    public static int orDefault(@Nullable Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /**
     * For limits and timeouts, where zero and negatives are not smaller requests but unset ones — a
     * model that means "no limit" sends nothing, not {@code 0}.
     */
    public static int positiveOrDefault(@Nullable Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static IllegalArgumentException missing(String name) {
        return new IllegalArgumentException(
                "Tool argument '" + name + "' is missing. Call the tool again with it filled in.");
    }
}
