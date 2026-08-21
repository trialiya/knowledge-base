package io.github.trialiya.kb.service.document;

import io.github.trialiya.kb.model.doc.dto.DocumentGrepMatch;
import io.github.trialiya.kb.utils.MarkdownSections;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Grep over one document's markdown, in the shape {@code git grep} answers in: matching lines
 * grouped into blocks with their context, numbered from 1, marked {@code :N:} / {@code -N-}.
 *
 * <p>Pure text in, {@link DocumentGrepMatch} blocks out — no repository, no transaction, which is
 * why it sits apart from {@link DocumentService} the way {@code GitGrep} sits apart from {@code
 * GitService}. It does not shell out to git for the same reason the tool exists at all: a document
 * body lives in the database, not in a working tree.
 */
final class DocumentGrep {

    /** Upper bound on context lines, matching {@code grepContent}. */
    static final int MAX_CONTEXT_LINES = 10;

    private DocumentGrep() {}

    /**
     * Compiles the search pattern the way {@code grepContent} does: always case-insensitive, a
     * regex only when asked for, a literal fragment otherwise.
     *
     * @throws IllegalArgumentException on a broken regex — with the syntax error in the message, so
     *     the model can fix the pattern instead of guessing why nothing matched
     */
    static Pattern compile(String pattern, boolean regex) {
        try {
            return Pattern.compile(
                    regex ? pattern : Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "pattern '"
                            + pattern
                            + "' is not a valid regular expression: "
                            + e.getDescription()
                            + ". Fix it, or pass regex=false to search for it literally.",
                    e);
        }
    }

    /**
     * All match blocks of one document, in document order.
     *
     * @param contextLines lines kept around each match; overlapping windows are merged into one
     *     block, as {@code grep -C} does
     * @param limit stop after this many blocks — the caller's budget across all documents
     */
    static List<DocumentGrepMatch> matches(
            long documentId,
            String title,
            String markdown,
            Pattern pattern,
            int contextLines,
            int limit) {
        if (markdown.isEmpty() || limit <= 0) {
            return List.of();
        }
        String[] lines = markdown.split("\n", -1);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        List<MarkdownSections.Section> sections = MarkdownSections.parse(markdown);
        int[] lineOffsets = lineOffsets(lines);

        List<DocumentGrepMatch> blocks = new ArrayList<>();
        int cursor = 0;
        while (cursor < hits.size() && blocks.size() < limit) {
            int firstHit = hits.get(cursor);
            int from = Math.max(0, firstHit - contextLines);
            int to = Math.min(lines.length - 1, firstHit + contextLines);
            // Hits whose context windows touch belong to one block, so the reader sees a fragment
            // rather than the same lines repeated once per hit. Without context there are no
            // windows to touch: every hit is its own block, exactly as `git grep -C0` prints them.
            int last = cursor;
            while (contextLines > 0
                    && last + 1 < hits.size()
                    && hits.get(last + 1) - contextLines <= to + 1) {
                last++;
                to = Math.min(lines.length - 1, hits.get(last) + contextLines);
            }

            blocks.add(
                    new DocumentGrepMatch(
                            documentId,
                            title,
                            sectionPathAt(sections, lineOffsets[firstHit]),
                            firstHit + 1,
                            blockText(lines, from, to, hits, cursor, last, contextLines)));
            cursor = last + 1;
        }
        return List.copyOf(blocks);
    }

    /**
     * The block as the model reads it: the plain line when no context was asked for (same as {@code
     * grepContent} with {@code contextLines=0}), otherwise every line prefixed with its number and
     * marked as match or context.
     */
    private static String blockText(
            String[] lines,
            int from,
            int to,
            List<Integer> hits,
            int firstHitIndex,
            int lastHitIndex,
            int contextLines) {
        if (contextLines == 0) {
            return lines[from];
        }
        List<Integer> matched = hits.subList(firstHitIndex, lastHitIndex + 1);
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            char sep = matched.contains(i) ? ':' : '-';
            text.append(sep).append(i + 1).append(sep).append(lines[i]).append('\n');
        }
        return text.toString();
    }

    /** Offset of each line's first character, so a hit line maps back into the raw markdown. */
    private static int[] lineOffsets(String[] lines) {
        int[] offsets = new int[lines.length];
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            offsets[i] = offset;
            offset += lines[i].length() + 1; // the '\n' split() removed
        }
        return offsets;
    }

    /**
     * The innermost section covering {@code offset}. Sections nest — a parent's range contains its
     * children's — so the last one that starts at or before the offset is the deepest, and that is
     * the path an edit should be addressed to.
     */
    private static @Nullable String sectionPathAt(
            List<MarkdownSections.Section> sections, int offset) {
        String path = null;
        for (MarkdownSections.Section section : sections) {
            if (section.startOffset() > offset) {
                break;
            }
            if (offset < section.endOffset()) {
                path = section.path();
            }
        }
        return path;
    }
}
