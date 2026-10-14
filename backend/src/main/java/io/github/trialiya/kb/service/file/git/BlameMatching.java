package io.github.trialiya.kb.service.file.git;

import java.util.Locale;
import java.util.Set;

/**
 * How {@code git blame} pairs a line with its earlier version.
 *
 * <p>The blame column in Files uses {@link #EXACT} — what GitHub's blame view and a bare {@code
 * git blame} show, so the column agrees with both. The model's {@code getBlame} asks "who wrote
 * this logic", which a re-indentation ({@code try} wrapped around a block) or a method moved down
 * the file answers wrongly: the lines go to whoever touched the whitespace or moved them.
 *
 * @param ignoreWhitespace {@code -w}: a change in whitespace alone does not make a line new. It
 *     also hides an uncommitted edit that only re-indents: those lines keep their last commit
 * @param detectMoves {@code -M}: lines moved or copied within the file keep the commit that wrote
 *     them — a copy, too, goes to the commit that wrote the original. Within the file only — {@code -C} (across files) walks every file a commit touched and
 *     is left out for its cost
 */
public record BlameMatching(boolean ignoreWhitespace, boolean detectMoves) {

    /** Line by line, as GitHub shows it. */
    public static final BlameMatching EXACT = new BlameMatching(false, false);

    /** Extensions of the languages whose indentation is syntax: there a re-indent changes behaviour. */
    private static final Set<String> INDENTED_EXTENSIONS =
            Set.of("py", "pyi", "pyw", "yaml", "yml", "mk", "haml", "pug", "slim", "sass", "styl", "coffee", "nim");

    /** Makefiles go without an extension: a recipe line is a tab, not spaces. */
    private static final Set<String> INDENTED_NAMES = Set.of("makefile", "gnumakefile");

    /**
     * Whether whitespace is meaning in this file — indentation that is syntax (Python, YAML,
     * Makefiles, …), where {@code -w} would credit a line dedented out of a block to the commit that
     * first wrote it and hide the change that broke it. Read off the name only; a language this
     * list misses is blamed with {@code -w} unless the caller turns it off.
     */
    public static boolean whitespaceMatters(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return INDENTED_NAMES.contains(name) || (dot > 0 && INDENTED_EXTENSIONS.contains(name.substring(dot + 1)));
    }
}
