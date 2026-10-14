package io.github.trialiya.kb.service.file.git;

/**
 * How {@code git blame} pairs a line with its earlier version.
 *
 * <p>The blame column in Files uses {@link #EXACT} — what GitHub's blame view and a bare {@code
 * git blame} show, so the column agrees with both. The model's {@code getBlame} asks "who wrote
 * this logic", which a re-indentation ({@code try} wrapped around a block) or a method moved down
 * the file answers wrongly: the lines go to whoever touched the whitespace or moved them.
 *
 * @param ignoreWhitespace {@code -w}: a change in whitespace alone does not make a line new
 * @param detectMoves {@code -M}: lines moved or copied within the file keep the commit that wrote
 *     them. Within the file only — {@code -C} (across files) walks every file a commit touched and
 *     is left out for its cost
 */
public record BlameMatching(boolean ignoreWhitespace, boolean detectMoves) {

    /** Line by line, as GitHub shows it. */
    public static final BlameMatching EXACT = new BlameMatching(false, false);
}
