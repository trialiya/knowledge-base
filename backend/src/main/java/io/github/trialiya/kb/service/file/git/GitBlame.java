package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code git blame --porcelain} command line and the shape of its output — pure text, like
 * {@link GitGrep}: an argument list in, raw output lines back, {@link GitFileBlame.Hunk}s out, with
 * no repository state involved.
 */
final class GitBlame {

    /** The hash git gives lines no commit holds yet: an uncommitted edit in the working tree. */
    static final String UNCOMMITTED = "0".repeat(40);

    /**
     * What a line of {@code .git-blame-ignore-revs} may name: a hash, full or abbreviated. Git
     * itself refuses the whole run on a line it cannot resolve, so anything else — a comment, a
     * blank, a typo — is dropped before it reaches the command line.
     */
    private static final Pattern REVISION_LINE = Pattern.compile("[0-9a-fA-F]{7,40}");

    private static final Pattern HEADER = Pattern.compile("([0-9a-f]{40}) (\\d+) (\\d+)(?: (\\d+))?");

    private GitBlame() {}

    /**
     * One {@code git blame} invocation: {@code git blame --porcelain [--ignore-rev <sha>…]
     * [<commit>] -- <path>}.
     *
     * <p>{@code --porcelain} rather than {@code --line-porcelain}: the commit's fields are printed
     * once, on its first hunk, and the parser keeps them — half the output on a file whose lines
     * mostly come from a few commits.
     *
     * <p>The ignored revisions travel one {@code --ignore-rev} each rather than as {@code
     * --ignore-revs-file}: the file that names them lives in the tree being blamed — for a snapshot
     * that is the commit's tree, not the working copy — and an option per hash reads the same
     * whichever tree it came from. It also spares git's own reading of that file, which refuses the
     * whole run on a line it cannot resolve.
     *
     * @param commit when non-null, the file as of this commit is blamed instead of the working tree.
     *     Callers pass a resolved hash, never user input: an argument starting with {@code -} would
     *     be read as an option
     */
    static List<String> args(String path, List<String> ignoredRevs, @Nullable String commit) {
        List<String> args = new ArrayList<>(List.of("git", "blame", "--porcelain"));
        for (String rev : ignoredRevs) {
            args.add("--ignore-rev");
            args.add(rev);
        }
        if (commit != null) {
            args.add(commit);
        }
        args.add("--");
        args.add(path);
        return args;
    }

    /**
     * The revisions {@code .git-blame-ignore-revs} names, out of its text: one hash per line,
     * {@code #} comments and everything that is not a hash left out.
     */
    static List<String> ignoredRevs(String ignoreFile) {
        return ignoreFile
                .lines()
                .map(line -> {
                    int hash = line.indexOf('#');
                    return (hash < 0 ? line : line.substring(0, hash)).strip();
                })
                .filter(line -> REVISION_LINE.matcher(line).matches())
                .toList();
    }

    /**
     * Parses {@code --porcelain} output into hunks, in line order.
     *
     * <p>The layout: a header {@code <sha> <orig-line> <final-line> [<lines-in-hunk>]} opens a hunk
     * ({@code orig-line} — where the hunk starts in that commit's file — becomes its {@code
     * sourceLine});
     * the first time a commit appears it is followed by its fields ({@code author}, {@code
     * author-mail}, {@code author-time}, {@code author-tz}, {@code summary}, …), one {@code key
     * value} per line; every line of the file follows its own header, prefixed by a tab. The count
     * is present on the first header of a hunk only, and the headers of the hunk's remaining lines
     * are skipped over here: a hunk is one record, not one per line. {@code filename} — the path as
     * of that commit — comes with the commit's fields on its first hunk, and again on every hunk
     * of a commit blame reached under more than one name (a merge across a rename), so it is
     * taken per hunk where printed and from the commit's first appearance otherwise.
     *
     * @throws IllegalStateException if the output does not have that shape — git changed it, or the
     *     run was cut short
     */
    static List<GitFileBlame.Hunk> parse(List<String> lines) {
        Map<String, Meta> metas = new HashMap<>();
        List<GitFileBlame.Hunk> hunks = new ArrayList<>();
        // The path printed for each hunk, by hunk index; null where git printed none.
        List<@Nullable String> hunkPaths = new ArrayList<>();
        @Nullable Meta current = null;
        for (String line : lines) {
            var header = HEADER.matcher(line);
            if (header.matches()) {
                String sha = header.group(1);
                if (header.group(4) != null) {
                    hunks.add(bare(
                            sha,
                            Integer.parseInt(header.group(2)),
                            Integer.parseInt(header.group(3)),
                            Integer.parseInt(header.group(4))));
                    hunkPaths.add(null);
                }
                current = metas.computeIfAbsent(sha, s -> new Meta());
                continue;
            }
            if (line.startsWith("\t")) {
                continue;
            }
            if (current == null) {
                throw new IllegalStateException("git blame output starts without a header: " + line);
            }
            if (line.startsWith(FILENAME) && !hunks.isEmpty()) {
                hunkPaths.set(hunks.size() - 1, line.substring(FILENAME.length()));
            }
            current.take(line);
        }
        List<GitFileBlame.Hunk> result = new ArrayList<>(hunks.size());
        for (int i = 0; i < hunks.size(); i++) {
            GitFileBlame.Hunk open = hunks.get(i);
            String hash = open.hash();
            Meta meta = hash == null ? null : metas.get(hash);
            result.add(meta == null ? open : meta.fill(open, hunkPaths.get(i)));
        }
        return List.copyOf(result);
    }

    private static final String FILENAME = "filename ";

    /**
     * A hunk as its header names it, before the commit's fields are known. The source line is kept
     * for committed lines only: for an uncommitted edit git numbers the working copy itself, and
     * there is no snapshot to point into.
     */
    private static GitFileBlame.Hunk bare(String sha, int sourceLine, int fromLine, int count) {
        boolean uncommitted = UNCOMMITTED.equals(sha);
        return new GitFileBlame.Hunk(
                fromLine, count, uncommitted ? null : sha, null, null, null, null, uncommitted ? null : sourceLine);
    }

    /** One commit's fields, gathered off the {@code key value} lines that follow its first header. */
    private static final class Meta {
        private @Nullable String author;
        private @Nullable String summary;
        private @Nullable String path;
        private long time;
        private @Nullable String tz;

        void take(String line) {
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            String value = space < 0 ? "" : line.substring(space + 1);
            switch (key) {
                case "author" -> author = value;
                case "author-time" -> time = Long.parseLong(value);
                case "author-tz" -> tz = value;
                case "summary" -> summary = value;
                // The fallback for hunks without a filename of their own is the commit's first
                // one; a later one belongs to the hunk it was printed on (see parse).
                case "filename" -> path = path == null ? value : path;
                default -> {
                    // author-mail, committer-*, previous, boundary: nothing the column shows.
                }
            }
        }

        /**
         * @param hunkPath the path git printed on this very hunk, or {@code null} to use the one
         *     printed on the commit's first appearance
         */
        GitFileBlame.Hunk fill(GitFileBlame.Hunk open, @Nullable String hunkPath) {
            String hash = open.hash();
            if (hash == null) {
                return open;
            }
            return new GitFileBlame.Hunk(
                    open.fromLine(),
                    open.lineCount(),
                    hash,
                    author,
                    date(),
                    summary,
                    hunkPath == null ? path : hunkPath,
                    open.sourceLine());
        }

        /** The author's moment in the author's own offset; an offset git printed oddly falls back to UTC. */
        private @Nullable OffsetDateTime date() {
            if (tz == null) {
                return null;
            }
            try {
                return Instant.ofEpochSecond(time).atOffset(ZoneOffset.of(tz));
            } catch (DateTimeException e) {
                return Instant.ofEpochSecond(time).atOffset(ZoneOffset.UTC);
            }
        }
    }
}
