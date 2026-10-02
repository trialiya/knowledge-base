package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The text side of tracing where a substring entered a line — pure, like {@link GitBlame}: what
 * counts as the substring being there, the line of a file version, a hunk turned into a step. The
 * runs of git that walk the line back are {@link GitBlameRunner#origin}'s.
 */
final class LineOrigin {

    /**
     * Versions walked before giving up. A line rewritten this many times while keeping the substring
     * is rare; each step is one blame of one line, and the whole walk shares one deadline anyway.
     */
    static final int MAX_STEPS = 30;

    /** Characters of a version's text kept in the answer: a minified line is one line too. */
    static final int MAX_TEXT_CHARS = 500;

    private LineOrigin() {}

    /**
     * The substring as the search found it: literal and case-insensitive, as {@code git grep -i
     * -F} matched it.
     */
    static Pattern needle(String query) {
        return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Line {@code line} (1-based) of the file's bytes, numbered as git numbers them, without its
     * line ending; {@code null} past the last line.
     */
    static @Nullable String lineAt(byte[] bytes, int line) {
        int start = 0;
        for (int current = 1; current < line; current++) {
            int newline = indexOf(bytes, start);
            if (newline < 0) {
                return null;
            }
            start = newline + 1;
        }
        if (start >= bytes.length) {
            return null;
        }
        int end = indexOf(bytes, start);
        int stop = end < 0 ? bytes.length : end;
        if (stop > start && bytes[stop - 1] == '\r') {
            stop--;
        }
        return new String(bytes, start, stop - start, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** The hunk of a one-line blame, as a step of the walk, with the text that version had. */
    static GitLineOrigin.Step step(GitFileBlame.Hunk hunk, String hash, String path, int line, String text) {
        return new GitLineOrigin.Step(hash, hunk.author(), hunk.date(), hunk.summary(), path, line, cap(text));
    }

    private static String cap(String text) {
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "…";
    }
}
