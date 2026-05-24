package io.github.trialiya.kb.service.outline;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps file extensions to a canonical language identifier used throughout the Git tools. Pure
 * lookup — no I/O, no native dependencies.
 */
public final class LanguageDetector {

    private LanguageDetector() {}

    /** Extension (lowercase, without dot) → canonical language id. */
    private static final Map<String, String> BY_EXTENSION =
            Map.ofEntries(
                    Map.entry("java", "java"),
                    Map.entry("js", "javascript"),
                    Map.entry("mjs", "javascript"),
                    Map.entry("cjs", "javascript"),
                    Map.entry("jsx", "javascript"),
                    Map.entry("ts", "typescript"),
                    Map.entry("tsx", "typescript"),
                    Map.entry("py", "python"),
                    Map.entry("pyi", "python"),
                    Map.entry("pyw", "python"),
                    Map.entry("sql", "sql"));

    /**
     * Returns the canonical language id for a path, or {@code null} if the extension is unknown.
     *
     * @param path file path (only the extension is inspected)
     */
    @Nullable
    public static String detect(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase());
    }
}
