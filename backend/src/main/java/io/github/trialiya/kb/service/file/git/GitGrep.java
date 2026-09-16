package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code git grep} command line and the shape of its output — everything about the one
 * operation {@link GitService} cannot do through JGit, minus the policy of when to run it.
 *
 * <p>Kept apart from the service because it is pure text: an argument list in, raw output lines
 * back, {@link GitGrepMatch} blocks out, with no repository state involved.
 */
final class GitGrep {

    private GitGrep() {}

    /**
     * One {@code git grep} invocation: {@code git grep -n -i --heading --break --no-color
     * [--untracked --no-exclude-standard] [--fixed-strings|-E] [-C ctx] -e <pattern> [<commit>] [--
     * <pathspec>…]}.
     *
     * <p>{@code --heading --break} is what makes the output parseable at all. In git's default
     * layout every line starts with the path, and the path is separated from the line number by the
     * same {@code :}/{@code -} that may occur inside the path itself: {@code
     * 2024-01-15-notes.md:2:beta} has no reading that tells the file name from the line number.
     * With a heading the path is printed once, on a line of its own, and the lines under it carry
     * nothing but {@code <linenum><sep><text>}; {@code --break} puts a blank line before each
     * heading, so a heading is never mistaken for a line of a file whose name starts with digits.
     *
     * <p>{@code --no-color} for the same reason: {@code color.ui = always} in the host's gitconfig
     * paints the output even when nobody is looking at a terminal, and every line then arrives
     * wrapped in escape sequences that fit none of the shapes above.
     *
     * @param roots when non-null, the run covers untracked and {@code .gitignore}d files under
     *     these directories instead of the index
     * @param commit when non-null, the tree of this commit is searched instead of the index; git
     *     then prefixes every heading with {@code <commit>:}, which {@link #withoutCommitPrefix}
     *     strips before parsing. Callers pass a resolved hash, never user input: an argument
     *     starting with {@code -} would be read as an option
     */
    static List<String> args(
            String pattern,
            @Nullable String pathspec,
            boolean regex,
            int ctx,
            @Nullable List<String> roots,
            @Nullable String commit) {
        List<String> args =
                new ArrayList<>(
                        List.of("git", "grep", "-n", "-i", "--heading", "--break", "--no-color"));
        if (roots != null) {
            args.add("--untracked");
            args.add("--no-exclude-standard");
        }
        args.add(regex ? "-E" : "--fixed-strings");
        if (ctx > 0) {
            args.add("-C");
            args.add(String.valueOf(ctx));
        }
        // -e rather than a bare `--`: a commit has to follow the pattern, and after `--` git would
        // take it for a path.
        args.add("-e");
        args.add(pattern);
        if (commit != null) {
            args.add(commit);
        }
        if (pathspec != null || roots != null) {
            args.add("--"); // separates the pattern (and commit) from pathspecs
        }
        if (pathspec != null) {
            args.add(pathspec);
        }
        if (roots != null) {
            args.addAll(roots);
        }
        return args;
    }

    /**
     * The output of a run over a commit, with the {@code <commit>:} git puts in front of every
     * heading removed, so that {@link #parse} reads it exactly like a run over the index. Lines
     * that do not carry the prefix — everything but the headings — are left alone.
     */
    static List<String> withoutCommitPrefix(List<String> lines, String commit) {
        String prefix = commit + ":";
        return lines.stream()
                .map(line -> line.startsWith(prefix) ? line.substring(prefix.length()) : line)
                .toList();
    }

    /**
     * Parses raw {@code git grep --heading --break [-C ctx]} output into grouped {@link
     * GitGrepMatch} blocks.
     *
     * <p>The layout, for both values of ctx:
     *
     * <ul>
     *   <li>a heading — the path, alone on its line: the first line of the output, and every line
     *       that follows a blank one
     *   <li>{@code linenum:text} — match line (separator {@code :})
     *   <li>{@code linenum-text} — context line (separator {@code -}), with ctx &gt; 0 only
     *   <li>{@code --} — separator between two non-adjacent blocks of the <em>same</em> file;
     *       between files stands the blank line of {@code --break} instead
     * </ul>
     *
     * <p>Without context (ctx=0) every line under a heading is a match of its own and maps directly
     * to one match block.
     *
     * <p>With context the lines of one block are folded into one {@link GitGrepMatch} whose {@code
     * text} reproduces the git grep format ({@code :N:} for matches, {@code -N-} for context). The
     * {@code matchLine} field holds the line number of the first match in the block.
     */
    static List<GitGrepMatch> parse(List<String> lines, int ctx, int limit) {
        List<GitGrepMatch> results = new ArrayList<>();
        Block block = new Block(limit, results);
        // The first line of the output names a file; after that only a blank line announces one.
        boolean heading = true;
        @Nullable String path = null;

        for (String line : lines) {
            if (line.isBlank()) {
                if (block.flush()) return results;
                heading = true;
                continue;
            }
            if (heading) {
                if (block.flush()) return results;
                path = line;
                heading = false;
                continue;
            }
            if (line.equals("--")) {
                if (block.flush()) return results;
                continue;
            }
            // Defensive: git prints no line before the first heading, so a data line without a
            // path is output this parser does not understand — dropping it beats inventing a path.
            if (path == null) continue;
            @Nullable DataLine data = parseDataLine(line);
            if (data == null) continue;

            if (ctx == 0) {
                results.add(new GitGrepMatch(path, data.lineNum(), data.text()));
                if (results.size() >= limit) return results;
                continue;
            }
            block.append(path, data);
        }
        block.flush();
        return results;
    }

    /** One in-progress context block: the lines seen so far for one file, and its first match. */
    private static final class Block {

        private final int limit;
        private final List<GitGrepMatch> results;
        private final StringBuilder buf = new StringBuilder();

        private @Nullable String path;
        private int firstMatchLine = -1;

        Block(int limit, List<GitGrepMatch> results) {
            this.limit = limit;
            this.results = results;
        }

        void append(String linePath, DataLine data) {
            path = linePath;
            // ":N:text" for a match line, "-N-text" for a context line — the git grep format.
            char sep = data.isMatch() ? ':' : '-';
            buf.append(sep).append(data.lineNum()).append(sep).append(data.text()).append('\n');
            if (data.isMatch() && firstMatchLine < 0) {
                firstMatchLine = data.lineNum();
            }
        }

        /**
         * Emits the block, if it holds a match at all, and starts an empty one.
         *
         * @return true when the result limit is now reached and parsing should stop
         */
        boolean flush() {
            String finished = path;
            int matchLine = firstMatchLine;
            String text = buf.toString();
            path = null;
            firstMatchLine = -1;
            buf.setLength(0);

            if (finished == null || matchLine < 0 || results.size() >= limit) {
                return false;
            }
            results.add(new GitGrepMatch(finished, matchLine, text));
            return results.size() >= limit;
        }
    }

    /** One line of a file under a heading: its number, its text, and whether it matched. */
    private record DataLine(int lineNum, String text, boolean isMatch) {}

    /**
     * Parses one line under a heading: {@code <linenum><sep><text>}, where sep is {@code ':'} for a
     * match line and {@code '-'} for a context line. Returns {@code null} if the line does not have
     * that shape.
     */
    private static @Nullable DataLine parseDataLine(String line) {
        int sepIdx = 0;
        while (sepIdx < line.length() && Character.isDigit(line.charAt(sepIdx))) sepIdx++;
        if (sepIdx == 0 || sepIdx >= line.length()) return null;
        char sep = line.charAt(sepIdx);
        if (sep != ':' && sep != '-') return null;

        int lineNum;
        try {
            lineNum = Integer.parseInt(line.substring(0, sepIdx));
        } catch (NumberFormatException e) {
            return null; // a line number past int — not a file this search can point into
        }
        return new DataLine(lineNum, line.substring(sepIdx + 1), sep == ':');
    }
}
