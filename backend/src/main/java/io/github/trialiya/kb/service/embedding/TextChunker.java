package io.github.trialiya.kb.service.embedding;

import io.github.trialiya.kb.utils.MarkdownSections;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * Splits a text into overlapping chunks that fit within a configurable token budget.
 *
 * <h3>Why chunking?</h3>
 *
 * OpenAI's embedding models accept at most 8 192 tokens. Documents longer than that must be split;
 * the resulting per-chunk vectors are averaged by {@link EmbeddingService} to produce one
 * document-level embedding.
 *
 * <h3>Splitting strategy (cascading)</h3>
 *
 * <ol>
 *   <li>Markdown sections, as {@link MarkdownSections#parse} reads them — a heading stays with its
 *       body. Small sections are packed together; a section that does not fit is cut below, and
 *       every piece after the first repeats the heading line. A fenced code block is never cut
 *       unless it alone exceeds the budget, and no overlap is carried across a section boundary.
 *   <li>Paragraph boundaries ({@code \n\n}) — preferred natural unit.
 *   <li>Sentence boundaries ({@code [.!?]}) — used when a paragraph is too long.
 *   <li>Word boundaries — last resort for very long sentences.
 * </ol>
 *
 * Adjacent chunks share {@link #overlapTokens} worth of content so that a query spanning a boundary
 * is still answered correctly.
 *
 * <h3>Token estimation</h3>
 *
 * We approximate 1 token ≈ {@value #CHARS_PER_TOKEN} characters (conservative for Latin text; CJK
 * text will be under-counted, but still safe for the purpose of staying under the hard limit).
 */
@Builder
public class TextChunker {

    /** Conservative char-per-token ratio. */
    static final int CHARS_PER_TOKEN = 4;

    /** Default max tokens per chunk — well under the 8 192-token model limit. */
    public static final int DEFAULT_MAX_TOKENS = 512;

    /** Default overlap between adjacent chunks in tokens. */
    public static final int DEFAULT_OVERLAP_TOKENS = 64;

    @Builder.Default
    private final int maxTokens = DEFAULT_MAX_TOKENS;

    @Builder.Default
    private final int overlapTokens = DEFAULT_OVERLAP_TOKENS;

    // ── Singleton with defaults ───────────────────────────────────────────────

    private static final TextChunker DEFAULT_INSTANCE = TextChunker.builder().build();

    public static TextChunker defaults() {
        return DEFAULT_INSTANCE;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Splits {@code text} into chunks. Returns a single-element list when the text fits in one
     * chunk. Never returns an empty list for non-blank input.
     */
    public List<String> split(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String stripped = text.strip();
        int maxChars = maxTokens * CHARS_PER_TOKEN;

        if (stripped.length() <= maxChars) {
            return List.of(stripped);
        }

        List<Unit> units = toAtomicUnits(stripped, maxChars);
        return mergeWithOverlap(units, maxChars);
    }

    /** Returns {@code true} if the text is short enough to embed without splitting. */
    public boolean fitsInOneChunk(@Nullable String text) {
        return text == null || text.length() <= (long) maxTokens * CHARS_PER_TOKEN;
    }

    // ── Step 1: break text into atomic units ─────────────────────────────────

    /** A piece of text that is never split further when merging; {@code section} is its owner. */
    private record Unit(String text, int section) {}

    private List<Unit> toAtomicUnits(String text, int maxChars) {
        List<Unit> units = new ArrayList<>();
        List<MarkdownSections.Section> sections = MarkdownSections.parse(text);
        for (int i = 0; i < sections.size(); i++) {
            MarkdownSections.Section section = sections.get(i);
            // A section's own text ends at the next heading of any level: its subsections are
            // sections of their own in the flat list.
            int end = i + 1 < sections.size() ? sections.get(i + 1).startOffset() : text.length();
            addSection(text.substring(section.startOffset(), end).strip(), section.level(), i, maxChars, units);
        }
        return units;
    }

    private void addSection(String own, int level, int id, int maxChars, List<Unit> units) {
        if (own.isEmpty()) return;
        if (own.length() <= maxChars) {
            units.add(new Unit(own, id));
            return;
        }
        String heading = level > 0 ? own.lines().findFirst().orElse("") : "";
        int budget = maxChars - heading.length() - 1;
        if (budget < maxChars / 2) {
            heading = "";
            budget = maxChars;
        }

        List<String> pieces = new ArrayList<>();
        int from = 0;
        for (int[] block : MarkdownSections.codeBlocks(own)) {
            addProse(own.substring(from, block[0]), budget, pieces);
            String code = own.substring(block[0], block[1]).strip();
            if (code.length() <= budget) {
                pieces.add(code);
            } else {
                splitByLines(code, budget, pieces);
            }
            from = block[1];
        }
        addProse(own.substring(from), budget, pieces);

        for (int k = 0; k < pieces.size(); k++) {
            String piece = pieces.get(k);
            units.add(new Unit(k == 0 || heading.isEmpty() ? piece : heading + "\n" + piece, id));
        }
    }

    private void addProse(String prose, int maxChars, List<String> out) {
        for (String para : prose.split("\\n{2,}")) {
            String p = para.strip();
            if (p.isEmpty()) continue;

            if (p.length() <= maxChars) {
                out.add(p);
            } else {
                splitBySentences(p, maxChars, out);
            }
        }
    }

    private void splitByLines(String text, int maxChars, List<String> out) {
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.length() > maxChars) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                splitByWords(line, maxChars, out);
                continue;
            }
            if (buf.length() + 1 + line.length() > maxChars && !buf.isEmpty()) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append('\n');
            buf.append(line);
        }
        if (!buf.isEmpty()) out.add(buf.toString());
    }

    private void splitBySentences(String text, int maxChars, List<String> out) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder buf = new StringBuilder();

        for (String sentence : sentences) {
            String s = sentence.strip();
            if (s.isEmpty()) continue;

            if (s.length() > maxChars) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString().strip());
                    buf.setLength(0);
                }
                splitByWords(s, maxChars, out);
                continue;
            }

            if (buf.length() + 1 + s.length() > maxChars && !buf.isEmpty()) {
                out.add(buf.toString().strip());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append(' ');
            buf.append(s);
        }
        if (!buf.isEmpty()) out.add(buf.toString().strip());
    }

    private void splitByWords(String text, int maxChars, List<String> out) {
        String[] words = text.split("\\s+");
        StringBuilder buf = new StringBuilder();

        for (String word : words) {
            if (buf.length() + 1 + word.length() > maxChars && !buf.isEmpty()) {
                out.add(buf.toString().strip());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append(' ');
            buf.append(word);
        }
        if (!buf.isEmpty()) out.add(buf.toString().strip());
    }

    // ── Step 2: merge units into overlapping chunks ───────────────────────────

    private List<String> mergeWithOverlap(List<Unit> units, int maxChars) {
        int overlapChars = overlapTokens * CHARS_PER_TOKEN;
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentSection = -1;

        for (Unit unit : units) {
            String text = unit.text();
            boolean fits =
                    current.isEmpty() ? text.length() <= maxChars : current.length() + 1 + text.length() <= maxChars;

            if (!fits && !current.isEmpty()) {
                String finished = current.toString().strip();
                chunks.add(finished);

                // The tail is a cut at a word, so it is only safe prose of the same section: across
                // a heading it would mislead, and in code it would start a block mid-way.
                String overlap =
                        unit.section() == currentSection && !finished.contains("```") && !finished.contains("~~~")
                                ? tailChars(finished, overlapChars)
                                : "";
                current.setLength(0);
                if (!overlap.isBlank() && overlap.length() + 1 + text.length() <= maxChars) {
                    current.append(overlap);
                }
            }

            if (!current.isEmpty()) current.append('\n');
            current.append(text);
            currentSection = unit.section();
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
        }

        return chunks;
    }

    private static String tailChars(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int start = text.length() - maxChars;
        while (start < text.length() && !Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.substring(start).strip();
    }
}
