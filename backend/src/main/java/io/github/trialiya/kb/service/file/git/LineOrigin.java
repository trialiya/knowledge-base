package io.github.trialiya.kb.service.file.git;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.RawText;

/**
 * The text side of tracing where a substring entered a line — pure, with no repository: what
 * counts as the substring being there, which old line a changed line came from, and when a block
 * counts as moved in from another file. The walk itself is {@link LineOriginTracer}'s.
 */
final class LineOrigin {

    /**
     * Versions walked before giving up. A line rewritten this many times while keeping the substring
     * is rare; each step is one blame of one line, and the whole walk shares one deadline anyway.
     */
    static final int MAX_STEPS = 30;

    /** Characters of a version's text kept in the answer: a minified line is one line too. */
    static final int MAX_TEXT_CHARS = 500;

    /**
     * Letters and digits a block must carry to count as moved in from another file — git's own
     * threshold for {@code blame -C}: shorter runs (a closing brace, a blank line, a bare {@code
     * return}) match by chance in any two files.
     */
    static final int MOVE_MIN_ALNUM = 40;

    private LineOrigin() {}

    /**
     * The substring as the search found it: literal and case-insensitive, as {@code git grep -i
     * -F} matched it.
     */
    static Pattern needle(String query) {
        return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * The old line a changed line came from, among the old side of its hunk ({@code from}…{@code
     * to - 1} of {@code old}): one that has the substring, the most like {@code line} when several
     * do; {@code -1} when none has it — then the substring entered with this change.
     *
     * <p>Asking for the substring, not just for similarity, is the point: a line added next to a
     * look-alike that shares the substring is not that look-alike's later version.
     */
    static int carrying(RawText old, int from, int to, Pattern needle, String line) {
        int best = -1;
        double bestScore = -1;
        for (int i = from; i < to; i++) {
            String candidate = old.getString(i);
            if (needle.matcher(candidate).find()) {
                double score = similarity(candidate, line);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
        }
        return best;
    }

    /** The old line most like {@code line} among {@code from}…{@code to - 1}: the version before. */
    static int closest(RawText old, int from, int to, String line) {
        int best = from;
        double bestScore = -1;
        for (int i = from; i < to; i++) {
            double score = similarity(old.getString(i), line);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * How alike two lines read, 0…1: the Dice coefficient over character pairs, indentation aside —
     * a renamed variable or an added argument keeps most pairs, an unrelated line keeps few.
     */
    static double similarity(String a, String b) {
        String x = a.strip();
        String y = b.strip();
        if (x.length() < 2 || y.length() < 2) {
            return x.equals(y) ? 1 : 0;
        }
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i + 1 < x.length(); i++) {
            pairs.merge(x.substring(i, i + 2), 1, Integer::sum);
        }
        int shared = 0;
        for (int i = 0; i + 1 < y.length(); i++) {
            String pair = y.substring(i, i + 2);
            Integer left = pairs.get(pair);
            if (left != null && left > 0) {
                shared++;
                pairs.put(pair, left - 1);
            }
        }
        return 2.0 * shared / (x.length() - 1 + y.length() - 1);
    }

    /** Whether two lines are the same text, indentation aside — how a moved line is matched. */
    static boolean sameLine(String a, String b) {
        return a.strip().equals(b.strip());
    }

    /** Letters and digits of a line: what a moved block is weighed by. */
    static int alnum(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetterOrDigit(line.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    static String cap(String text) {
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "…";
    }
}
