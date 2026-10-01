package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitGrepHits;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.GitGrepResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Content search over one repository: the {@code git grep} subprocess, its command line ({@link
 * GitGrep}) and the admission of untracked files ({@link VisibleFiles}). JGit has no grep, so the
 * search leaves the JVM through {@link GitReadProcess}; what is grep's own — the cut at a context
 * block boundary, exit 1 as "no match", a refused pattern — is read out of the output here.
 */
@Slf4j
final class GitGrepRunner {

    /**
     * How long one search may run, both {@code git grep} runs of an untracked search included. A
     * search is interactive on both of its entry points — a tool call inside a run and the search
     * page — and a walk that has not answered by then is better cut short than left to occupy the
     * repository for as long as the walk takes.
     */
    static final Duration GREP_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Raw output lines one run is read up to; git is stopped once they are in. The cap on match
     * blocks does not bound the output on its own: a pattern that matches every line of a large
     * repository writes the repository, and holding it to keep a handful of blocks is what this
     * prevents. With no context a block is one line, so the cap is exact there (see {@link
     * #outputLines}); with context the ceiling is generous enough that no realistic answer reaches
     * it, and a run that does ends at its last complete block — and with nothing from that run at
     * all when its whole output turned out to be one block that never finished.
     */
    static final int MAX_OUTPUT_LINES = 20_000;

    /** Most match blocks one search hands out; {@code grepContent} names the same bound to the model. */
    static final int MAX_RESULTS = 200;

    /**
     * What one search found, and whether a run stopped at its output ceiling before git was done —
     * the matches past that point were never read, so their absence says nothing.
     */
    private record Found(List<GitGrepMatch> matches, boolean cut) {}

    /** Output of one {@code git grep} run, and whether it was stopped before git had finished. */
    private record Lines(List<String> lines, boolean cut) {}

    private final Repository repository;
    private final VisibleFiles visible;
    private final GitReadProcess git;

    GitGrepRunner(RepoPaths paths, Repository repository, VisibleFiles visible) {
        this(paths, repository, visible, GREP_TIMEOUT);
    }

    /** With the deadline spelled out — a test's way of seeing one expire. */
    GitGrepRunner(RepoPaths paths, Repository repository, VisibleFiles visible, Duration timeout) {
        this.repository = repository;
        this.visible = visible;
        this.git = new GitReadProcess(paths.root(), timeout);
    }

    /**
     * Searches the contents of tracked files for lines matching {@code pattern}, and says whether
     * there is more than it returned.
     *
     * <p>Delegates to {@code git grep}, which searches only tracked files (honouring {@code
     * .gitignore}) and is orders of magnitude faster than scanning the filesystem. Binary files are
     * skipped automatically by git grep.
     *
     * <p>The search is <b>literal by default</b> ({@code --fixed-strings}). Pass {@code regex=true}
     * to enable POSIX extended regular expressions. The search is always <b>case-insensitive</b>
     * ({@code -i}) because the AI often doesn't know exact casing.
     *
     * <p>When {@code contextLines > 0} the raw git grep output carries context lines alongside the
     * matches, in blocks. These are collapsed into one {@link GitGrepMatch} per contiguous block so
     * the caller sees grouped context rather than one record per raw line; the layout being read is
     * described in {@link GitGrep#parse}.
     *
     * <p>One block over the cap is asked of git, so that exactly {@code maxResults} blocks read as
     * complete and one more as cut. A run stopped at its output ceiling is cut whatever it holds:
     * with context that happens long before the cap ({@link #MAX_OUTPUT_LINES}), and a short or
     * empty list then means "not read", not "not there".
     *
     * @param pattern literal string or regex to search for
     * @param pathGlob optional glob to restrict search to matching paths (e.g. {@code "*.java"},
     *     {@code "src/main/**"}); null means all tracked files
     * @param regex if true, treat {@code pattern} as an extended regex; otherwise literal
     * @param rev optional — search the tree of this commit instead of the working tree: hash (full
     *     or short), branch, tag or {@code HEAD~2}, resolved through JGit first so only a hash ever
     *     reaches the command line. Untracked files have no place in a commit, so {@code
     *     includeUntracked} is ignored with it
     * @param contextLines number of context lines before and after each match (like grep -C); 0
     *     means match line only; capped at 10
     * @param maxResults maximum number of match blocks to return; capped at {@value #MAX_RESULTS}
     * @param includeUntracked also search the untracked files this project's {@code allow-globs}
     *     admit; off by default, so a plain search answers about the committed codebase
     * @return match blocks in order of appearance, at most {@code maxResults}; with {@code includeUntracked} the two runs are
     *     merged and the whole list comes back ordered by path instead, so a file's blocks stay
     *     together rather than splitting around the seam between the runs — what the second run
     *     contributed is marked {@code tracked=false}. Empty if nothing matched
     * @throws IllegalArgumentException if the revision is unknown or ambiguous, or the pattern is
     *     not a valid regular expression
     */
    GitGrepHits grepHits(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            @Nullable String rev,
            int contextLines,
            int maxResults,
            boolean includeUntracked) {
        int cap = Math.clamp(maxResults, 1, MAX_RESULTS);
        Found found = rev == null
                ? search(pattern, pathGlob, regex, contextLines, cap + 1, includeUntracked)
                : searchAt(rev, pattern, pathGlob, regex, contextLines, cap + 1);
        List<GitGrepMatch> matches = found.matches();
        boolean over = matches.size() > cap;
        return new GitGrepHits(over ? matches.subList(0, cap) : matches, over || found.cut());
    }

    /**
     * The search page's question: {@link #grepHits} without context, grouped by file.
     */
    GitGrepResult grepPage(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            @Nullable String rev,
            boolean includeUntracked,
            int maxResults) {
        GitGrepHits hits = grepHits(pattern, pathGlob, regex, rev, 0, maxResults, includeUntracked);
        return GitGrepResult.group(hits.matches(), hits.truncated());
    }

    /** The working-tree half of {@link #grepHits}, with the block limit already decided. */
    private Found search(
            String pattern,
            @Nullable String pathGlob,
            boolean regex,
            int contextLines,
            int limit,
            boolean includeUntracked) {
        int ctx = Math.min(Math.max(contextLines, 0), 10);

        if (!regex && (pattern.contains(".*") || pattern.contains("|"))) {
            log.warn("grepContent: pattern '{}' looks like regex but regex=false — using literal match", pattern);
        }

        String glob = pathGlob == null || pathGlob.isBlank() ? null : RepoPaths.toForwardSlashes(pathGlob.strip());
        long deadline = git.deadline();
        Lines trackedRun =
                exec(GitGrep.args(pattern, glob, regex, ctx, null, null), ctx, outputLines(ctx, limit), deadline);
        List<GitGrepMatch> tracked = GitGrep.parse(trackedRun.lines(), ctx, limit);
        // No roots left to search is not "search everywhere": without a pathspec the untracked run
        // would sweep the whole working tree.
        if (!includeUntracked || visible.allowGlobRoots().isEmpty()) {
            return new Found(tracked, trackedRun.cut());
        }

        // A second, separately bounded run: `--untracked` cannot be added to the one above without
        // also dragging in every other untracked file in the repository, and
        // `--no-exclude-standard`
        // would send it through node_modules and build/. Rooting it at the globs' own directories
        // keeps the walk the size of the named area.
        // Unbounded in blocks, since the filters below decide what counts, and bounded in lines
        // by the output ceiling alone, since the roots are a named area and not the repository.
        Lines extraRun = exec(
                GitGrep.args(pattern, null, regex, ctx, visible.allowGlobRoots(), null),
                ctx,
                MAX_OUTPUT_LINES,
                deadline);
        List<GitGrepMatch> extra = GitGrep.parse(extraRun.lines(), ctx, Integer.MAX_VALUE);
        Set<String> trackedPaths = Set.copyOf(visible.trackedPaths());
        @Nullable Pathspec pathspec = Pathspec.of(glob);
        List<GitGrepMatch> merged = new ArrayList<>(tracked);
        extra.stream()
                // The roots are wider than the globs, and `--untracked` reports tracked files too;
                // `glob` is re-applied by hand because it is spent on the pathspec above.
                .filter(m -> !trackedPaths.contains(m.path()))
                .filter(m -> visible.matchesAllowGlobs(m.path()))
                .filter(m -> pathspec == null || pathspec.matches(m.path()))
                // Что осталось после фильтров — по определению untracked, и сказано это в самой
                // записи: после слияния двух прогонов по порядку уже не видно, откуда она.
                .map(GitGrepMatch::untracked)
                .forEach(merged::add);
        // Cut only once everything invisible is gone, or a large untracked area would spend the
        // whole cap on matches nobody gets to see.
        List<GitGrepMatch> found = merged.stream()
                .sorted(Comparator.comparing(GitGrepMatch::path))
                .limit(limit)
                .toList();
        return new Found(found, trackedRun.cut() || extraRun.cut());
    }

    /** The commit half of {@link #grepHits}, with the block limit already decided. */
    private Found searchAt(
            String rev, String pattern, @Nullable String pathGlob, boolean regex, int contextLines, int limit) {
        int ctx = Math.min(Math.max(contextLines, 0), 10);
        String commit = CommitFiles.commitOf(repository, rev.strip()).name();
        String glob = pathGlob == null || pathGlob.isBlank() ? null : RepoPaths.toForwardSlashes(pathGlob.strip());
        Lines run = exec(
                GitGrep.args(pattern, glob, regex, ctx, null, commit), ctx, outputLines(ctx, limit), git.deadline());
        return new Found(GitGrep.parse(GitGrep.withoutCommitPrefix(run.lines(), commit), ctx, limit), run.cut());
    }

    /**
     * The index of the last line that ends a context block, or -1 when the output holds no complete
     * one. Two lines end a block: the {@code --} between two blocks of one file, and the blank line
     * {@code --break} puts between two files — a run cut off in the middle of the first block of
     * its second file has no {@code --} anywhere, and everything read before that file still
     * stands.
     */
    private static int lastBlockBoundary(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.equals("--") || line.isBlank()) return i;
        }
        return -1;
    }

    /**
     * Output lines that are enough for {@code limit} blocks: exact without context, where a block
     * is one line — plus the two lines {@code --heading --break} can spend on it, since in the
     * worst case every match sits in a file of its own and arrives preceded by a blank line and a
     * heading.
     */
    private static int outputLines(int ctx, int limit) {
        return ctx == 0 ? 3 * limit : MAX_OUTPUT_LINES;
    }

    /**
     * Runs {@code git grep} and reads at most {@code maxLines} of what it prints: past that git is
     * stopped and the lines already in hand are the answer, since the caller has no use for the
     * rest.
     *
     * <p>Exit code 1 is git's "no match" and comes back as the (empty) output. 128 is git's own
     * refusal, told apart by what it complains about on stderr: the pattern (a broken regular
     * expression) is the caller's mistake and surfaces as {@link IllegalArgumentException} carrying
     * git's words, so the caller can show why nothing came back instead of an empty list; anything
     * else git refuses — a repository it cannot read — is a failure of this side.
     *
     * @param ctx the context lines the command asks for; with none, every line of output is a block
     *     of its own and the cut falls on a boundary by itself
     * @param deadline {@link System#nanoTime()} past which the run is killed
     * @throws IllegalArgumentException if git refused the pattern
     * @throws GitReadTimeoutException if git did not answer by {@code deadline}
     * @throws IllegalStateException if git failed in any other way
     */
    private Lines exec(List<String> command, int ctx, int maxLines, long deadline) {
        GitReadProcess.Output out = git.run(command, maxLines, deadline);
        List<String> lines = out.lines();
        if (out.cut()) {
            // Without context every line is a block of its own and all of them stand (a heading
            // left dangling at the end names a file no line of which was read, and the parser
            // drops it). With context the run ended inside a block, and that block is dropped —
            // the last boundary is where the last complete one ended, and a buffer without one
            // holds no complete block at all.
            if (ctx == 0) {
                return new Lines(lines, true);
            }
            int lastSeparator = lastBlockBoundary(lines);
            if (lastSeparator < 0) {
                log.warn("Git command filled {} lines with one unfinished block: {}", maxLines, command);
                return new Lines(List.of(), true);
            }
            return new Lines(lines.subList(0, lastSeparator), true);
        }
        if (out.exit() > 1) {
            String said = out.said();
            log.warn("Git command exited {}: {} → {}", out.exit(), command, said);
            String badPattern = out.exit() == 128 ? patternComplaint(out.stderr()) : null;
            if (badPattern != null) {
                throw new IllegalArgumentException(badPattern);
            }
            throw new IllegalStateException("git grep exited " + out.exit() + ": " + said);
        }
        // Exit 1 is git grep's "no matches" — not an error, the output is simply empty.
        return new Lines(lines, false);
    }

    /**
     * Git's complaint about the pattern, if that is what it refused: the {@code fatal:} line that
     * names the {@code -e} option, without the prefix and without the "-e option," git puts before
     * the offending regex — the pattern travels as {@code -e}'s value, so that is how git names it.
     * {@code null} when git refused something else.
     */
    private static @Nullable String patternComplaint(List<String> stderr) {
        return stderr.stream()
                .filter(line -> line.startsWith("fatal:"))
                .map(line -> line.substring("fatal:".length()).strip())
                .filter(line -> line.startsWith("-e option, "))
                .map(line -> line.substring("-e option, ".length()))
                .findFirst()
                .orElse(null);
    }
}
