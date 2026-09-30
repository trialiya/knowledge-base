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

    /** Length of the short hash in a hunk — git's own default abbreviation. */
    private static final int SHORT_HASH_LEN = 7;

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
     * <p>The layout: a header {@code <sha> <orig-line> <final-line> [<lines-in-hunk>]} opens a hunk;
     * the first time a commit appears it is followed by its fields ({@code author}, {@code
     * author-mail}, {@code author-time}, {@code author-tz}, {@code summary}, …), one {@code key
     * value} per line; every line of the file follows its own header, prefixed by a tab. The count
     * is present on the first header of a hunk only, and the headers of the hunk's remaining lines
     * are skipped over here: a hunk is one record, not one per line.
     *
     * @throws IllegalStateException if the output does not have that shape — git changed it, or the
     *     run was cut short
     */
    static List<GitFileBlame.Hunk> parse(List<String> lines) {
        Map<String, Meta> metas = new HashMap<>();
        List<GitFileBlame.Hunk> hunks = new ArrayList<>();
        @Nullable Meta current = null;
        for (String line : lines) {
            var header = HEADER.matcher(line);
            if (header.matches()) {
                String sha = header.group(1);
                if (header.group(4) != null) {
                    hunks.add(bare(sha, Integer.parseInt(header.group(3)), Integer.parseInt(header.group(4))));
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
            current.take(line);
        }
        List<GitFileBlame.Hunk> result = new ArrayList<>(hunks.size());
        for (GitFileBlame.Hunk open : hunks) {
            String hash = open.hash();
            Meta meta = hash == null ? null : metas.get(hash);
            result.add(meta == null ? open : meta.fill(open));
        }
        return List.copyOf(result);
    }

    /** A hunk as its header names it, before the commit's fields are known. */
    private static GitFileBlame.Hunk bare(String sha, int fromLine, int count) {
        return new GitFileBlame.Hunk(
                fromLine, count, UNCOMMITTED.equals(sha) ? null : sha, null, null, null, null, null);
    }

    /** One commit's fields, gathered off the {@code key value} lines that follow its first header. */
    private static final class Meta {
        private @Nullable String author;
        private @Nullable String email;
        private @Nullable String summary;
        private long time;
        private @Nullable String tz;

        void take(String line) {
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            String value = space < 0 ? "" : line.substring(space + 1);
            switch (key) {
                case "author" -> author = value;
                case "author-mail" -> email = stripAngles(value);
                case "author-time" -> time = Long.parseLong(value);
                case "author-tz" -> tz = value;
                case "summary" -> summary = value;
                default -> {
                    // committer-*, previous, filename, boundary: nothing the column shows.
                }
            }
        }

        GitFileBlame.Hunk fill(GitFileBlame.Hunk open) {
            String hash = open.hash();
            if (hash == null) {
                return open;
            }
            return new GitFileBlame.Hunk(
                    open.fromLine(),
                    open.lineCount(),
                    hash,
                    hash.substring(0, SHORT_HASH_LEN),
                    author,
                    email,
                    date(),
                    summary);
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

        private static String stripAngles(String mail) {
            return mail.startsWith("<") && mail.endsWith(">") ? mail.substring(1, mail.length() - 1) : mail;
        }
    }
}
