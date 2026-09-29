package io.github.trialiya.kb.service.document;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
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
     * link text, group 2 the (URL-encoded) path <em>with</em> whatever query the model appended;
     * the {@code &project=} and the optional {@code #Lx-Ly} range are both dropped on export, like
     * the app origin itself — an export is read outside the app, where neither means anything.
     * Links written before projects were named carry no {@code &project=} and match just the same.
     *
     * <p>Group 2 deliberately stops only at {@code )} or {@code #}, never at {@code &}: a file may
     * be called {@code Q&A.md}, and cutting the path there would export half a name. Which part of
     * it is the project is decided in {@link #withoutProject}, not by the character class.
     */
    private static final Pattern FILE_LINK =
            Pattern.compile("\\[([^\\]]+)]\\(/files\\?path=([^)#\\n]+)(?:#L\\d+(?:-L\\d+)?)?\\)");

    /**
     * That same query where a link actually carries one: at the very end of the target, holding an
     * id {@code ProjectCatalog} would accept. Both halves matter because the path in front of it is
     * unencoded — {@code notes&project=x/readme.md} is a legal file name, and only a tail that
     * could be an id at all is the parameter this class writes. A file named exactly {@code
     * X&project=<valid id>} stays indistinguishable from a stamped link and is read as the latter;
     * nothing in the link tells those two apart.
     */
    private static final Pattern PROJECT_QUERY = Pattern.compile("&project=[a-z0-9][a-z0-9._-]*$");

    /**
     * The revision a link to a file as of a commit carries, in front of the project: {@code
     * /files?path=PATH&rev=HASH&project=ID}. Only a hex hash counts, for the same reason {@link
     * #PROJECT_QUERY} insists on an id — the path in front of it is unencoded, and only a tail that
     * could be a hash at all is the parameter the model was told to write.
     */
    private static final Pattern REV_QUERY = Pattern.compile("&rev=([0-9a-fA-F]{4,64})$");

    /**
     * Whole Markdown link to a commit: a {@code /files} link with a query and no path — {@code
     * [0123456](/files?rev=HASH&project=ID)} as the model writes it, or the address the app itself
     * shows for a commit, pasted in. Group 1 is the link text, group 2 the query; whether it names
     * a revision and no path is decided in {@link #flattenCommitLinks}.
     */
    private static final Pattern COMMIT_LINK =
            Pattern.compile("\\[([^\\]]+)]\\(/files/?\\?([^)#\\s]*)\\)");

    /** How many hash characters an export shows — git's own short form. */
    private static final int SHORT_HASH = 7;

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
     * Flattens {@code [text](/files?path=PATH[&rev=HASH][&project=ID][#Lx-Ly])} to plain {@code
     * text (PATH)}, or {@code text (PATH @ HASH7)} for a file as of a commit. An export has no
     * running app to serve {@code /files}, so the link is reduced to the file's name and its
     * repo-relative path — plus the commit, without which a quote of an old version would read as
     * today's file.
     */
    public static String flattenFileLinks(String text) {
        Matcher m = FILE_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String pathWithRev = withoutProject(m.group(2));
            Matcher rev = REV_QUERY.matcher(pathWithRev);
            boolean hasRev = rev.find();
            // The model writes paths unencoded, so a literal '+' is part of the file name — shield
            // it from URLDecoder's application/x-www-form-urlencoded '+'→space rule, while still
            // decoding any %xx escapes.
            String path =
                    URLDecoder.decode(
                            (hasRev ? pathWithRev.substring(0, rev.start()) : pathWithRev)
                                    .replace("+", "%2B"),
                            StandardCharsets.UTF_8);
            String where = hasRev ? path + " @ " + shortHash(rev.group(1)) : path;
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + " (" + where + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Flattens a commit link ({@code [text](/files?rev=HASH[&project=ID])}) to plain text: the link
     * text alone when it already is the hash (the usual {@code [0123456](...)}), otherwise {@code
     * text (HASH7)} — "this commit" with the hash dropped would cite nothing. A {@code /files} link
     * without a hex revision, or with a path, is not a commit link and is left alone.
     */
    public static String flattenCommitLinks(String text) {
        Matcher m = COMMIT_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String rev = commitOf(m.group(2));
            String label = m.group(1);
            String replacement;
            if (rev == null) {
                replacement = m.group(0);
            } else {
                String bare = label.replace("`", "").strip();
                boolean labelIsHash =
                        bare.length() >= 4
                                && rev.toLowerCase(Locale.ROOT)
                                        .startsWith(bare.toLowerCase(Locale.ROOT));
                replacement = labelIsHash ? label : label + " (" + shortHash(rev) + ")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The hex revision a {@code /files} query names, or {@code null} if it names a path too. */
    private static @Nullable String commitOf(String query) {
        String rev = null;
        for (String param : query.split("&")) {
            if (param.startsWith("path=")) {
                return null;
            }
            if (param.startsWith("rev=")) {
                rev = param.substring("rev=".length());
            }
        }
        return rev != null && rev.matches("[0-9a-fA-F]{4,64}") ? rev : null;
    }

    private static String shortHash(String hash) {
        return hash.length() > SHORT_HASH ? hash.substring(0, SHORT_HASH) : hash;
    }

    /** The path alone — the project the model appended is not part of the file's name. */
    private static String withoutProject(String pathWithQuery) {
        Matcher m = PROJECT_QUERY.matcher(pathWithQuery);
        return m.find() ? pathWithQuery.substring(0, m.start()) : pathWithQuery;
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
