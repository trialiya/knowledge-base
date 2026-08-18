package io.github.trialiya.kb.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Translation of Markdown links between the two worlds a document lives in: {@code /?doc=ID} inside
 * the running app, relative file paths inside an export.
 *
 * <p>Kept apart from both the export and the sync because the two directions must stay exact
 * inverses of each other — a round trip through the file system has to give the identical link
 * back. Paths here are plain {@code /}-joined strings, never {@link java.nio.file.Path}: the same
 * code has to produce entry names for a ZIP stream and file names on disk, and on Windows a real
 * {@code Path} would hand back backslashes for one of them.
 */
public final class DocumentLinkRewriter {

    /** Internal KB doc link inside a Markdown link target: {@code (/?doc=123)}. */
    private static final Pattern DOC_LINK = Pattern.compile("\\(/\\?doc=(\\d+)\\)");

    /**
     * Whole Markdown link pointing at a repository file, e.g. {@code
     * [GitService.java](/files?path=backend/.../GitService.java&project=kb#L1-L10)}. Group 1 is the
     * link text, group 2 the (URL-encoded) path; the {@code &project=} the model appends and the
     * optional {@code #Lx-Ly} range are both dropped on export, like the app origin itself — an
     * export is read outside the app, where neither means anything. Links written before projects
     * were named carry no {@code &project=} and match just the same.
     */
    private static final Pattern FILE_LINK =
            Pattern.compile(
                    "\\[([^\\]]+)]\\(/files\\?path=([^)#&]+)(?:&[^)#]*)?(?:#L\\d+(?:-L\\d+)?)?\\)");

    /**
     * A repo-file link target whose path is not followed by anything — no {@code &project=} yet.
     * Anchored on the {@code )} or {@code #} that must come next, so a link already carrying a
     * project (there the next character is {@code &}) is left alone and stamping stays idempotent.
     */
    private static final Pattern FILE_LINK_WITHOUT_PROJECT =
            Pattern.compile("(/files\\?path=[^)#&\\s]+)(?=[)#])");

    /** Any Markdown link target — the reverse direction has to inspect every one of them. */
    private static final Pattern ANY_LINK_TARGET = Pattern.compile("]\\(([^)\\s]*)\\)");

    private DocumentLinkRewriter() {}

    // ── App → export ─────────────────────────────────────────────────────────

    /**
     * Rewrites every {@code (/?doc=ID)} in {@code text} to a path relative to {@code sourceFile}.
     * Ids missing from {@code idToFile} (deleted document, or a subtree export that does not
     * contain the target) keep their original link — a dangling relative path would be worse than
     * an app link that at least still resolves in the app.
     *
     * @param sourceFile export-relative file the text is being written to
     * @param idToFile document id → export-relative file holding that document's body
     */
    public static String toRelativeLinks(
            String text, String sourceFile, Map<Long, String> idToFile) {
        Matcher m = DOC_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String target = idToFile.get(Long.parseLong(m.group(1)));
            String replacement =
                    target == null ? m.group(0) : "(" + relativize(sourceFile, target) + ")";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Flattens {@code [text](/files?path=PATH[&project=ID][#Lx-Ly])} to plain {@code text (PATH)}.
     * An export has no running app to serve {@code /files}, so the link is reduced to the file's
     * name and its repo-relative path.
     */
    public static String flattenFileLinks(String text) {
        Matcher m = FILE_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            // The model writes paths unencoded, so a literal '+' is part of the file name — shield
            // it from URLDecoder's application/x-www-form-urlencoded '+'→space rule, while still
            // decoding any %xx escapes.
            String path = URLDecoder.decode(m.group(2).replace("+", "%2B"), StandardCharsets.UTF_8);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + " (" + path + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Names {@code projectId} in every repo-file link of {@code text} that does not name a project
     * yet, or returns {@code null} when there was nothing to change.
     *
     * <p>Written before projects existed, {@code /files?path=…} means "the default project" — which
     * is only stable while the default is. The moment a deployment puts another repository first,
     * every such link in stored history would point at a file that merely shares a path. Stamping
     * the project that was meant at the time freezes the answer.
     *
     * <p>Idempotent by construction (see {@link #FILE_LINK_WITHOUT_PROJECT}), so a re-run cannot
     * produce {@code &project=a&project=b}.
     */
    public static @Nullable String stampProject(String text, String projectId) {
        Matcher m = FILE_LINK_WITHOUT_PROJECT.matcher(text);
        if (!m.find()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        String suffix = Matcher.quoteReplacement("&project=" + projectId);
        do {
            m.appendReplacement(out, "$1" + suffix);
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    // ── Export → app ─────────────────────────────────────────────────────────

    /**
     * The inverse of {@link #toRelativeLinks}: every relative Markdown link that resolves to a
     * known export file becomes {@code (/?doc=ID)} again.
     *
     * <p>Anything that is not a relative path into the export is left alone — absolute links,
     * external URLs, {@code mailto:}, bare anchors — as is any relative path that does not land on
     * a file the import knows about (an image next to the document, a link into a part of the tree
     * that was not imported).
     *
     * @param sourceFile export-relative file the text was read from
     * @param fileToId export-relative body file → document id
     * @return the rewritten text, or {@code null} when nothing changed — callers use that to skip a
     *     database write entirely
     */
    public static @Nullable String toDocLinks(
            String text, String sourceFile, Function<String, @Nullable Long> fileToId) {
        Matcher m = ANY_LINK_TARGET.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String target = m.group(1);
            Long id = isRelative(target) ? fileToId.apply(resolve(sourceFile, target)) : null;
            if (id == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement("](/?doc=" + id + ")"));
                changed = true;
            }
        }
        m.appendTail(out);
        return changed ? out.toString() : null;
    }

    /** True when {@code text} holds at least one link that could resolve inside the export. */
    public static boolean hasRelativeLinks(String text) {
        Matcher m = ANY_LINK_TARGET.matcher(text);
        while (m.find()) {
            if (isRelative(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRelative(String target) {
        return !target.isBlank()
                && !target.startsWith("/")
                && !target.startsWith("#")
                && !target.contains("://")
                && !target.startsWith("mailto:");
    }

    // ── Path arithmetic on '/'-joined strings ────────────────────────────────

    /** Path of {@code toFile} as seen from the directory holding {@code fromFile}. */
    public static String relativize(String fromFile, String toFile) {
        List<String> from = segments(parentDir(fromFile));
        List<String> to = segments(toFile);

        int common = 0;
        while (common < from.size()
                && common < to.size() - 1
                && from.get(common).equals(to.get(common))) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("../".repeat(from.size() - common));
        for (int i = common; i < to.size(); i++) {
            sb.append(to.get(i));
            if (i < to.size() - 1) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    /**
     * Resolves {@code relative} against the directory of {@code fromFile}, normalising {@code .}
     * and {@code ..}. Returns an empty string when the path climbs above the export root — no
     * export file can ever be named that, so the lookup simply misses.
     */
    public static String resolve(String fromFile, String relative) {
        Deque<String> stack = new ArrayDeque<>(segments(parentDir(fromFile)));
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (stack.isEmpty()) {
                    return "";
                }
                stack.removeLast();
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }

    /** Directory part of a {@code /}-joined path, {@code ""} at the root. */
    public static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }
}
