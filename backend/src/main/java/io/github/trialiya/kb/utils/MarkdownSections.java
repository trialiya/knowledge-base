package io.github.trialiya.kb.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a markdown text into heading-based sections so AI tools can read/update one section
 * instead of the whole document.
 *
 * <p>Model: a section is an ATX heading ({@code #}…{@code ######}) plus everything up to the next
 * heading of the same or higher level — i.e. the whole subtree including subsections. Text before
 * the first heading forms a special {@value #PREAMBLE_PATH} section. Headings inside fenced code
 * blocks (``` / ~~~) are ignored, including a block opened on a list-item line ({@code - ```sh}).
 * Setext headings ({@code ===} / {@code ---} underlines) are NOT supported. Lines may end in {@code
 * \n} or {@code \r\n}: a text synced from a Windows checkout keeps its CRLF, and offsets always
 * point into the text as given.
 *
 * <p>Sections are addressed by a human-readable path of ancestor titles joined with {@value
 * #PATH_SEPARATOR} (e.g. {@code "Установка > Docker"}). Duplicate paths get an occurrence suffix:
 * the second {@code "FAQ > Вопрос"} becomes {@code "FAQ > Вопрос[2]"}, and the suffix stays in the
 * paths of its subsections — {@code "FAQ[2] > Вопрос"} lives under the second {@code "FAQ"}. Paths
 * are computed over the whole document in one pass, so they are stable as long as the text does not
 * change. {@code common/preview/sectionAnchor.js} on the frontend recomputes them by the same rule.
 */
public final class MarkdownSections {

    /** Path of the pseudo-section holding text before the first heading. */
    public static final String PREAMBLE_PATH = "_preamble";

    private static final String PATH_SEPARATOR = " > ";

    /** ATX heading: 0–3 leading spaces, 1–6 hashes, then space + title (or nothing). */
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$");

    /**
     * Code fence line: indent (group 1), list-item markers the fence may follow on the same line
     * (group 2, e.g. {@code "- "} or {@code "1. "}), 3+ backticks or tildes (group 3), the rest
     * (group 4). How much indent is allowed depends on whether the line opens or closes a block —
     * see {@link #scanHeadings}.
     */
    private static final Pattern FENCE = Pattern.compile("^( *)((?:(?:[-*+]|\\d{1,9}[.)])[ \\t]+)*)(`{3,}|~{3,})(.*)$");

    private MarkdownSections() {}

    /**
     * One section of the document.
     *
     * @param path stable address: ancestor titles joined with {@value #PATH_SEPARATOR}, plus {@code
     *     [n]} suffix for duplicates; {@value #PREAMBLE_PATH} for the preamble
     * @param level heading level 1–6; 0 for the preamble
     * @param title heading text without leading/trailing hashes; empty for the preamble
     * @param startOffset offset of the heading line start (inclusive)
     * @param endOffset offset of the next same-or-higher-level heading, or text length (exclusive)
     * @param subsections number of direct child headings inside the section
     */
    public record Section(String path, int level, String title, int startOffset, int endOffset, int subsections) {

        /** Size of the whole subtree (heading + body + subsections) in characters. */
        public int chars() {
            return endOffset - startOffset;
        }
    }

    /**
     * Parses the text into a flat, document-ordered list of sections. A blank/empty text yields an
     * empty list; a text without any heading yields a single {@value #PREAMBLE_PATH} section.
     */
    public static List<Section> parse(String markdown) {
        int length = markdown.length();
        List<RawHeading> headings = scan(markdown).headings();

        List<Section> sections = new ArrayList<>();
        int firstHeadingOffset = headings.isEmpty() ? length : headings.get(0).offset();
        if (firstHeadingOffset > 0 && !markdown.substring(0, firstHeadingOffset).isBlank()) {
            sections.add(new Section(PREAMBLE_PATH, 0, "", 0, firstHeadingOffset, 0));
        }

        // Ancestors with their final paths, suffix included: a child of the second "A" is
        // "A[2] > X", addressed under the parent it really belongs to.
        List<Ancestor> stack = new ArrayList<>();
        Map<String, Integer> pathCounts = new HashMap<>();
        for (int i = 0; i < headings.size(); i++) {
            RawHeading h = headings.get(i);

            while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= h.level()) {
                stack.remove(stack.size() - 1);
            }
            String path =
                    stack.isEmpty() ? h.title() : stack.get(stack.size() - 1).path() + PATH_SEPARATOR + h.title();
            int occurrence = pathCounts.merge(path, 1, Integer::sum);
            String finalPath = occurrence == 1 ? path : path + "[" + occurrence + "]";
            stack.add(new Ancestor(h.level(), finalPath));

            int end = length;
            int subsections = 0;
            int childLevel = Integer.MAX_VALUE;
            for (int j = i + 1; j < headings.size(); j++) {
                RawHeading next = headings.get(j);
                if (next.level() <= h.level()) {
                    end = next.offset();
                    break;
                }
                // A direct child is any subtree heading not nested under a previous subsection.
                if (next.level() <= childLevel) {
                    subsections++;
                    childLevel = next.level();
                }
            }
            sections.add(new Section(finalPath, h.level(), h.title(), h.offset(), end, subsections));
        }
        return sections;
    }

    /**
     * Replaces the {@code section} span of {@code markdown} with {@code newContent} (trimmed),
     * keeping a blank-line separator before the following section.
     */
    public static String replaceSection(String markdown, Section section, String newContent) {
        String body = newContent.strip();
        String prefix = markdown.substring(0, section.startOffset());
        String suffix = markdown.substring(section.endOffset());
        if (body.isEmpty()) {
            return prefix + suffix;
        }
        return prefix + body + (suffix.isEmpty() ? "\n" : "\n\n") + suffix;
    }

    /**
     * Inserts {@code newContent} (trimmed) before or after the {@code anchor} section subtree,
     * keeping blank-line separators on both sides.
     */
    public static String insertSection(String markdown, Section anchor, String newContent, boolean before) {
        String body = newContent.strip();
        int offset = before ? anchor.startOffset() : anchor.endOffset();
        String prefix = markdown.substring(0, offset);
        String suffix = markdown.substring(offset);

        StringBuilder out = new StringBuilder(prefix);
        if (!prefix.isEmpty() && !prefix.endsWith("\n\n")) {
            out.append(prefix.endsWith("\n") ? "\n" : "\n\n");
        }
        out.append(body);
        out.append(suffix.isEmpty() ? "\n" : "\n\n");
        if (suffix.startsWith("\n")) {
            suffix = suffix.substring(1);
        }
        return out.append(suffix).toString();
    }

    /**
     * Replaces the title on the {@code section} heading line with {@code newTitle}, keeping the
     * heading level and the section body untouched. Must not be called for the preamble.
     */
    public static String renameHeading(String markdown, Section section, String newTitle) {
        int lineEnd = markdown.indexOf('\n', section.startOffset());
        if (lineEnd == -1) {
            lineEnd = markdown.length();
        } else if (lineEnd > section.startOffset() && markdown.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        return markdown.substring(0, section.startOffset())
                + "#".repeat(section.level())
                + " "
                + newTitle.strip()
                + markdown.substring(lineEnd);
    }

    private record RawHeading(int offset, int level, String title) {}

    private record Ancestor(int level, String path) {}

    /** Headings and fenced code blocks of one pass; a block is {@code {start, end}} offsets. */
    private record Scan(List<RawHeading> headings, List<int[]> codeBlocks) {}

    /**
     * Whether the first non-blank line of {@code content} is an ATX heading — by the same {@link
     * #HEADING} rule that {@link #parse} uses, so a bare {@code #} counts here as it does there.
     */
    public static boolean startsWithHeading(String content) {
        String stripped = content.strip();
        int newline = stripped.indexOf('\n');
        return HEADING.matcher(newline == -1 ? stripped : stripped.substring(0, newline))
                .matches();
    }

    /**
     * Applies {@code transform} to the text between fenced code blocks and leaves the blocks
     * themselves untouched, so a rewrite meant for prose never reaches code that merely looks like
     * it (a sample link, a {@code #} comment). Fences are recognised as in {@link #parse}.
     */
    public static String transformOutsideCode(String markdown, UnaryOperator<String> transform) {
        StringBuilder out = new StringBuilder(markdown.length());
        int from = 0;
        for (int[] block : scan(markdown).codeBlocks()) {
            out.append(transform.apply(markdown.substring(from, block[0])));
            out.append(markdown, block[0], block[1]);
            from = block[1];
        }
        return out.append(transform.apply(markdown.substring(from))).toString();
    }

    /**
     * The mirror of {@link #transformOutsideCode}: {@code transform} gets each fenced code block
     * whole (opening line to closing line, or to the end of the text when it never closes) and the
     * prose between blocks is kept as is.
     */
    public static String transformCodeBlocks(String markdown, UnaryOperator<String> transform) {
        StringBuilder out = new StringBuilder(markdown.length());
        int from = 0;
        for (int[] block : scan(markdown).codeBlocks()) {
            out.append(markdown, from, block[0]);
            out.append(transform.apply(markdown.substring(block[0], block[1])));
            from = block[1];
        }
        return out.append(markdown, from, markdown.length()).toString();
    }

    /** The info string of a code block's opening line: its language, when it names one. */
    public static String fenceInfo(String codeBlock) {
        int newline = codeBlock.indexOf('\n');
        Matcher fence = FENCE.matcher(newline == -1 ? codeBlock : codeBlock.substring(0, newline));
        return fence.matches() ? fence.group(4).strip() : "";
    }

    /** Collects ATX headings with their offsets, skipping fenced code blocks, and the blocks. */
    private static Scan scan(String markdown) {
        List<RawHeading> headings = new ArrayList<>();
        List<int[]> codeBlocks = new ArrayList<>();
        int blockStart = 0;
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        // Column the open block's content starts at: 0 at top level, past the marker in a list
        // item ("- ```" → 2), where the closing fence sits indented to match that content.
        int fenceColumn = 0;

        int pos = 0;
        int length = markdown.length();
        while (pos < length) {
            int newline = markdown.indexOf('\n', pos);
            int lineEnd = newline == -1 ? length : newline;
            String line = markdown.substring(pos, lineEnd);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                int indent = fence.group(1).length();
                String listMarkers = fence.group(2);
                String marker = fence.group(3);
                String rest = fence.group(4);
                if (!inFence) {
                    // A list marker means the line is a (possibly nested) list item, whose fence
                    // may sit at any depth; a bare fence at 4+ spaces is an indented code block.
                    if ((indent <= 3 || !listMarkers.isEmpty()) && opensFence(marker, rest)) {
                        inFence = true;
                        blockStart = pos;
                        fenceChar = marker.charAt(0);
                        fenceLength = marker.length();
                        fenceColumn = indent + listMarkers.length();
                    }
                } else if (listMarkers.isEmpty()
                        && indent <= fenceColumn + 3
                        && marker.charAt(0) == fenceChar
                        && marker.length() >= fenceLength
                        && rest.isBlank()) {
                    inFence = false;
                    codeBlocks.add(new int[] {blockStart, Math.min(lineEnd + 1, length)});
                }
            } else if (!inFence) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    headings.add(new RawHeading(pos, heading.group(1).length(), cleanTitle(heading.group(2))));
                }
            }
            pos = lineEnd + 1;
        }
        if (inFence) {
            codeBlocks.add(new int[] {blockStart, length});
        }
        return new Scan(headings, codeBlocks);
    }

    /**
     * Whether a fence-looking line really opens a code block: after a backtick marker the info
     * string may not contain a backtick (CommonMark), so {@code ```js``` text} is inline code and
     * must not hide every heading below it.
     */
    private static boolean opensFence(String marker, String infoString) {
        return marker.charAt(0) == '~' || infoString.indexOf('`') < 0;
    }

    /** Strips optional trailing closing hashes: {@code "Title ###"} → {@code "Title"}. */
    private static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String title = raw.strip();
        if (title.matches("#+")) {
            return "";
        }
        return title.replaceFirst("[ \\t]+#+$", "").strip();
    }
}
