package io.github.trialiya.kb.service;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;

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

    @Builder.Default private final int maxTokens = DEFAULT_MAX_TOKENS;

    @Builder.Default private final int overlapTokens = DEFAULT_OVERLAP_TOKENS;

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
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String stripped = text.strip();
        int maxChars = maxTokens * CHARS_PER_TOKEN;

        if (stripped.length() <= maxChars) {
            return List.of(stripped);
        }

        List<String> units = toAtomicUnits(stripped, maxChars);
        return mergeWithOverlap(units, maxChars);
    }

    /** Returns {@code true} if the text is short enough to embed without splitting. */
    public boolean fitsInOneChunk(String text) {
        return text == null || text.length() <= (long) maxTokens * CHARS_PER_TOKEN;
    }

    // ── Step 1: break text into atomic units ─────────────────────────────────

    private List<String> toAtomicUnits(String text, int maxChars) {
        List<String> units = new ArrayList<>();

        for (String para : text.split("\\n{2,}")) {
            String p = para.strip();
            if (p.isEmpty()) continue;

            if (p.length() <= maxChars) {
                units.add(p);
            } else {
                splitBySentences(p, maxChars, units);
            }
        }
        return units;
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

            if (buf.length() + 1 + s.length() > maxChars) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString().strip());
                    buf.setLength(0);
                }
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
            if (buf.length() + 1 + word.length() > maxChars) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString().strip());
                    buf.setLength(0);
                }
            }
            if (!buf.isEmpty()) buf.append(' ');
            buf.append(word);
        }
        if (!buf.isEmpty()) out.add(buf.toString().strip());
    }

    // ── Step 2: merge units into overlapping chunks ───────────────────────────

    private List<String> mergeWithOverlap(List<String> units, int maxChars) {
        int overlapChars = overlapTokens * CHARS_PER_TOKEN;
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String unit : units) {
            boolean fits =
                    current.isEmpty()
                            ? unit.length() <= maxChars
                            : current.length() + 1 + unit.length() <= maxChars;

            if (!fits && !current.isEmpty()) {
                String finished = current.toString().strip();
                chunks.add(finished);

                String overlap = tailChars(finished, overlapChars);
                current.setLength(0);
                if (!overlap.isBlank()) {
                    current.append(overlap);
                }
            }

            if (!current.isEmpty()) current.append('\n');
            current.append(unit);
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
