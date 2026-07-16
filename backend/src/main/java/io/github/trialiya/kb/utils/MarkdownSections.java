package io.github.trialiya.kb.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a markdown text into heading-based sections so AI tools can read/update one section
 * instead of the whole document.
 *
 * <p>Model: a section is an ATX heading ({@code #}…{@code ######}) plus everything up to the next
 * heading of the same or higher level — i.e. the whole subtree including subsections. Text before
 * the first heading forms a special {@value #PREAMBLE_PATH} section. Headings inside fenced code
 * blocks (``` / ~~~) are ignored. Setext headings ({@code ===} / {@code ---} underlines) are NOT
 * supported.
 *
 * <p>Sections are addressed by a human-readable path of ancestor titles joined with {@value
 * #PATH_SEPARATOR} (e.g. {@code "Установка > Docker"}). Duplicate paths get an occurrence suffix:
 * the second {@code "FAQ > Вопрос"} becomes {@code "FAQ > Вопрос[2]"}. Paths are computed over the
 * whole document in one pass, so they are stable as long as the text does not change.
 */
public final class MarkdownSections {

    /** Path of the pseudo-section holding text before the first heading. */
    public static final String PREAMBLE_PATH = "_preamble";

    private static final String PATH_SEPARATOR = " > ";

    /** ATX heading: 0–3 leading spaces, 1–6 hashes, then space + title (or nothing). */
    private static final Pattern HEADING =
            Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$");

    /** Opening/closing code fence: 0–3 leading spaces, 3+ backticks or tildes. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

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
    public record Section(
            String path, int level, String title, int startOffset, int endOffset, int subsections) {

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
        List<RawHeading> headings = scanHeadings(markdown);

        List<Section> sections = new ArrayList<>();
        int firstHeadingOffset = headings.isEmpty() ? length : headings.get(0).offset();
        if (firstHeadingOffset > 0 && !markdown.substring(0, firstHeadingOffset).isBlank()) {
            sections.add(new Section(PREAMBLE_PATH, 0, "", 0, firstHeadingOffset, 0));
        }

        List<RawHeading> stack = new ArrayList<>();
        Map<String, Integer> pathCounts = new HashMap<>();
        for (int i = 0; i < headings.size(); i++) {
            RawHeading h = headings.get(i);

            while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= h.level()) {
                stack.remove(stack.size() - 1);
            }
            stack.add(h);
            StringBuilder path = new StringBuilder();
            for (RawHeading ancestor : stack) {
                if (path.length() > 0) {
                    path.append(PATH_SEPARATOR);
                }
                path.append(ancestor.title());
            }
            int occurrence = pathCounts.merge(path.toString(), 1, Integer::sum);
            String finalPath = occurrence == 1 ? path.toString() : path + "[" + occurrence + "]";

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
            sections.add(
                    new Section(finalPath, h.level(), h.title(), h.offset(), end, subsections));
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
    public static String insertSection(
            String markdown, Section anchor, String newContent, boolean before) {
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
        }
        return markdown.substring(0, section.startOffset())
                + "#".repeat(section.level())
                + " "
                + newTitle.strip()
                + markdown.substring(lineEnd);
    }

    private record RawHeading(int offset, int level, String title) {}

    /** Collects ATX headings with their offsets, skipping fenced code blocks. */
    private static List<RawHeading> scanHeadings(String markdown) {
        List<RawHeading> headings = new ArrayList<>();
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;

        int pos = 0;
        int length = markdown.length();
        while (pos < length) {
            int newline = markdown.indexOf('\n', pos);
            int lineEnd = newline == -1 ? length : newline;
            String line = markdown.substring(pos, lineEnd);

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceChar = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceChar
                        && marker.length() >= fenceLength
                        && fence.group(2).isBlank()) {
                    inFence = false;
                }
            } else if (!inFence) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    headings.add(
                            new RawHeading(
                                    pos, heading.group(1).length(), cleanTitle(heading.group(2))));
                }
            }
            pos = lineEnd + 1;
        }
        return headings;
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
