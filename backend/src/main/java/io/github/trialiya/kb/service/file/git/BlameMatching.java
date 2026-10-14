package io.github.trialiya.kb.service.file.git;

import java.util.Locale;
import java.util.Set;

/**
 * How {@code git blame} pairs a line with its earlier version.
 *
 * <p>Two questions, two answers. "Which commit last touched this line" — the blame column in Files,
 * GitHub's blame view, and the model hunting the change that broke something — is {@link #EXACT}:
 * any edit counts, a re-indent and a moved line included. "Who wrote this logic" is {@link
 * #authorship}: a block wrapped in a {@code try} or a method moved down the file stays with whoever
 * wrote it, not with whoever re-indented or moved it.
 *
 * @param ignoreWhitespace {@code -w}: a change in whitespace alone does not make a line new — also
 *     inside string literals, and an uncommitted edit that only re-indents does not show
 * @param detectMoves {@code -M}: lines moved or copied within the file keep the commit that wrote
 *     them; a copy goes to the commit that wrote the original, a reordering to whoever wrote the
 *     lines. Within the file only — {@code -C} (across files) walks every file a commit touched
 *     and is left out for its cost
 */
record BlameMatching(boolean ignoreWhitespace, boolean detectMoves) {

    /** Any edit counts: the commit that last touched each line, as GitHub shows it. */
    static final BlameMatching EXACT = new BlameMatching(false, false);

    /** Extensions of the languages whose indentation is syntax. */
    private static final Set<String> INDENTED_EXTENSIONS = Set.of(
            "py", "pyi", "pyw", "pyx", "pxd", "bzl", "smk", "yaml", "yml", "mk", "mak", "make", "haml", "pug", "slim",
            "sass", "styl", "coffee", "nim", "hs", "lhs", "fsx");

    /** The same by whole file name — build files that go without an extension of their own. */
    private static final Set<String> INDENTED_NAMES = Set.of(
            "makefile",
            "gnumakefile",
            "bsdmakefile",
            "makefile.am",
            "makefile.in",
            "build.bazel",
            "workspace.bazel",
            "snakefile",
            "sconstruct",
            "sconscript");

    /**
     * Who wrote the lines of this file: {@code -M}, and {@code -w} unless indentation is syntax
     * here ({@link #whitespaceMatters}) — there a line dedented out of an {@code if} is a change in
     * behaviour, and {@code -w} would credit it to the commit that first wrote the line.
     */
    static BlameMatching authorship(String normalized) {
        return new BlameMatching(!whitespaceMatters(normalized), true);
    }

    /**
     * Whether indentation is syntax in this file (Python, YAML, Makefiles, Bazel, …). Read off the
     * name only; a language this list misses is blamed with {@code -w}.
     *
     * @param normalized a path {@link RepoPaths#normalize} has cleaned
     */
    static boolean whitespaceMatters(String normalized) {
        String name = RepoPaths.fileName(normalized).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return INDENTED_NAMES.contains(name) || (dot > 0 && INDENTED_EXTENSIONS.contains(name.substring(dot + 1)));
    }
}
