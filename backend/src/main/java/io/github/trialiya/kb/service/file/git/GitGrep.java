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
     * One {@code git grep} invocation: {@code git grep -n -i [--untracked --no-exclude-standard]
     * [--fixed-strings|-E] [-C ctx] -- <pattern> [-- <pathspec>…]}.
     *
     * @param roots when non-null, the run covers untracked and {@code .gitignore}d files under
     *     these directories instead of the index
     */
    static List<String> args(
            String pattern,
            @Nullable String pathspec,
            boolean regex,
            int ctx,
            @Nullable List<String> roots) {
        List<String> args = new ArrayList<>(List.of("git", "grep", "-n", "-i"));
        if (roots != null) {
            args.add("--untracked");
            args.add("--no-exclude-standard");
        }
        args.add(regex ? "-E" : "--fixed-strings");
        if (ctx > 0) {
            args.add("-C");
            args.add(String.valueOf(ctx));
        }
        args.add("--");
        args.add(pattern);
        if (pathspec != null || roots != null) {
            args.add("--"); // second -- separates the pattern from pathspecs
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
     * Parses raw {@code git grep [-C ctx]} output into grouped {@link GitGrepMatch} blocks.
     *
     * <p>Without context (ctx=0) each output line is {@code path:linenum:text} and maps directly to
     * one match block.
     *
     * <p>With context git grep emits:
     *
     * <ul>
     *   <li>{@code path:linenum:text} — match line (separator {@code :})
     *   <li>{@code path-linenum-text} — context line (separator {@code -})
     *   <li>{@code --} — group separator between non-adjacent blocks
     * </ul>
     *
     * Adjacent lines belonging to the same file+block are folded into one {@link GitGrepMatch}
     * whose {@code text} reproduces the git grep format ({@code :N:} for matches, {@code -N-} for
     * context). The {@code matchLine} field holds the line number of the first match in the block.
     */
    static List<GitGrepMatch> parse(List<String> lines, int ctx, int limit, String project) {
        List<GitGrepMatch> results = new ArrayList<>();

        if (ctx == 0) {
            // Simple case: one match per line, format "path:linenum:text"
            for (String line : lines) {
                if (line.isBlank()) continue;
                ParsedLine pl = parseLine(line);
                if (pl == null) continue;
                results.add(new GitGrepMatch(project, pl.path(), pl.lineNum(), pl.text()));
                if (results.size() >= limit) break;
            }
            return results;
        }

        // Context case: lines accumulate into a block until the next "--" separator (or,
        // defensively,
        // a change of path — git grep -C keeps one file's lines together between separators).
        Block block = new Block(project, limit, results);
        for (String line : lines) {
            if (line.equals("--")) {
                if (block.flush()) return results;
                continue;
            }
            if (line.isBlank()) continue;

            ParsedLine pl = parseLine(line);
            if (pl == null) continue;
            if (!pl.path().equals(block.path()) && block.flush()) return results;
            block.append(pl);
        }
        block.flush();
        return results;
    }

    /** One in-progress context block: the lines seen so far for one file, and its first match. */
    private static final class Block {

        private final String project;
        private final int limit;
        private final List<GitGrepMatch> results;
        private final StringBuilder buf = new StringBuilder();

        private @Nullable String path;
        private int firstMatchLine = -1;

        Block(String project, int limit, List<GitGrepMatch> results) {
            this.project = project;
            this.limit = limit;
            this.results = results;
        }

        @Nullable String path() {
            return path;
        }

        void append(ParsedLine pl) {
            path = pl.path();
            // ":N:text" for a match line, "-N-text" for a context line — the git grep format.
            char sep = pl.isMatch() ? ':' : '-';
            buf.append(sep).append(pl.lineNum()).append(sep).append(pl.text()).append('\n');
            if (pl.isMatch() && firstMatchLine < 0) {
                firstMatchLine = pl.lineNum();
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
            results.add(new GitGrepMatch(project, finished, matchLine, text));
            return results.size() >= limit;
        }
    }

    /** Parsed representation of one raw git grep output line. */
    private record ParsedLine(String path, int lineNum, String text, boolean isMatch) {}

    /**
     * Parses one raw git grep line.
     *
     * <p>Format: {@code <path><sep><linenum><sep><text>} where sep is {@code ':'} for match lines
     * and {@code '-'} for context lines.
     *
     * <p>Returns {@code null} if the line cannot be parsed.
     */
    private static @Nullable ParsedLine parseLine(String line) {
        // Find first separator that matches pattern <sep><digits><sep>
        int sepIdx = findFirstFieldSep(line);
        if (sepIdx < 0) return null;

        char sep = line.charAt(sepIdx);
        boolean isMatch = sep == ':';
        String path = line.substring(0, sepIdx);
        String rest = line.substring(sepIdx + 1); // "linenum<sep>text"

        // rest starts with digits followed by sep
        int numEnd = findLineNumEnd(rest);
        if (numEnd < 0) return null;

        int lineNum;
        try {
            lineNum = Integer.parseInt(rest.substring(0, numEnd));
        } catch (NumberFormatException e) {
            return null;
        }
        return new ParsedLine(path, lineNum, rest.substring(numEnd + 1), isMatch);
    }

    /**
     * Returns the index of the first {@code ':'} or {@code '-'} in {@code s} that is followed
     * immediately by one or more digits and then another {@code ':'} or {@code '-'} — i.e. the git
     * grep field separator between path and line number.
     */
    private static int findFirstFieldSep(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && c != '-') continue;
            int j = i + 1;
            if (j >= s.length() || !Character.isDigit(s.charAt(j))) continue;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j < s.length() && (s.charAt(j) == ':' || s.charAt(j) == '-')) return i;
        }
        return -1;
    }

    /**
     * Given {@code rest} = {@code "<digits><sep><text>"}, returns the index of {@code <sep>}.
     * Returns -1 if the string does not start with digits followed by {@code ':'} or {@code '-'}.
     */
    private static int findLineNumEnd(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i > 0 && i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == '-')) return i;
        return -1;
    }
}
