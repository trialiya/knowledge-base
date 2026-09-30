package io.github.trialiya.kb.service.file.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import io.github.trialiya.kb.utils.MarkdownSections;
import io.github.trialiya.kb.utils.MarkdownSections.Section;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outlines a markdown file by its ATX headings, with the same section model {@code
 * getDocumentOutline} applies to knowledge-base documents ({@link MarkdownSections}): a heading's
 * span is its whole subtree, so a subsection nests inside its parent by line range the same way a
 * method nests inside its class. Text before the first heading is reported as a {@value
 * MarkdownSections#PREAMBLE_PATH} symbol.
 *
 * <p>Each symbol is {@code kind} {@code "h1"}…{@code "h6"} (or {@code "preamble"}), {@code name} is
 * the heading text, and {@code signature} is the section path {@code getDocumentSection} would
 * accept for the same text — useful when the file is later imported as a document.
 */
public class MarkdownOutlineParser implements CodeOutlineParser {

    static final String LANGUAGE = "markdown";

    @Override
    public String name() {
        return LANGUAGE;
    }

    @Override
    public boolean supports(@Nullable String language) {
        return LANGUAGE.equals(language);
    }

    @Override
    public List<GitSymbol> parse(String language, String source) {
        int[] lineStarts = lineStarts(source);
        return MarkdownSections.parse(source).stream()
                .map(s -> toSymbol(s, lineStarts))
                .toList();
    }

    private static GitSymbol toSymbol(Section section, int[] lineStarts) {
        boolean preamble = section.level() == 0;
        int start = lineOf(section.startOffset(), lineStarts);
        // endOffset is exclusive; the section's last character is the newline ending its last line.
        int end = Math.max(start, lineOf(Math.max(section.endOffset() - 1, 0), lineStarts));
        return new GitSymbol(
                preamble ? "preamble" : "h" + section.level(),
                preamble ? MarkdownSections.PREAMBLE_PATH : section.title(),
                section.path(),
                start,
                end);
    }

    /** Offsets at which each line starts; index {@code i} holds the start of line {@code i + 1}. */
    private static int[] lineStarts(String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts[line++] = i + 1;
            }
        }
        return starts;
    }

    /** 1-based line number holding {@code offset}. */
    private static int lineOf(int offset, int[] lineStarts) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        return (idx >= 0 ? idx : -idx - 2) + 1;
    }
}
