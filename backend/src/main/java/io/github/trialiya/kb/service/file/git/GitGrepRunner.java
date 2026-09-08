package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Content search over one repository: the {@code git grep} subprocess, its command line ({@link
 * GitGrep}) and the admission of untracked files ({@link VisibleFiles}). The one part of {@link
 * GitService} that leaves the JVM — JGit has no grep — which is why the process handling (the
 * deadline, git's exit codes) lives here and nowhere else.
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
     * prevents. With no context a block is one line, so the cap is exact there; with context the
     * ceiling is generous enough that no realistic answer reaches it, and a run that does ends at
     * its last complete block — and with nothing from that run at all when its whole output turned
     * out to be one block that never finished.
     */
    static final int MAX_OUTPUT_LINES = 20_000;

    private final RepoPaths paths;
    private final Repository repository;
    private final VisibleFiles visible;
    private final Duration timeout;

    GitGrepRunner(RepoPaths paths, Repository repository, VisibleFiles visible) {
        this(paths, repository, visible, GREP_TIMEOUT);
    }

    /** With the deadline spelled out — a test's way of seeing one expire. */
    GitGrepRunner(RepoPaths paths, Repository repository, VisibleFiles visible, Duration timeout) {
        this.paths = paths;
        this.repository = repository;
        this.visible = visible;
        this.timeout = timeout;
    }

    /**
     * Searches the contents of tracked files for lines matching {@code pattern}.
     *
     * <p>Delegates to {@code git grep}, which searches only tracked files (honouring {@code
     * .gitignore}) and is orders of magnitude faster than scanning the filesystem. Binary files are
     * skipped automatically by git grep.
     *
     * <p>The search is <b>literal by default</b> ({@code --fixed-strings}). Pass {@code regex=true}
     * to enable POSIX extended regular expressions. The search is always <b>case-insensitive</b>
     * ({@code -i}) because the AI often doesn't know exact casing.
     *
     * <p>When {@code contextLines > 0} the raw git grep output contains context lines (prefixed
     * with {@code -}) and groups separated by {@code --}. These are collapsed into one {@link
     * GitGrepMatch} per contiguous block so the caller sees grouped context rather than one record
     * per raw line.
     *
     * @param pattern literal string or regex to search for
     * @param pathGlob optional glob to restrict search to matching paths (e.g. {@code "*.java"},
     *     {@code "src/main/**"}); null means all tracked files
     * @param regex if true, treat {@code pattern} as an extended regex; otherwise literal
     * @param contextLines number of context lines before and after each match (like grep -C); 0
     *     means match line only; capped at 10
     * @param maxResults maximum number of match blocks to return; capped at 200
     * @param includeUntracked also search the untracked files this project's {@code allow-globs}
     *     admit; off by default, so a plain search answers about the committed codebase
     * @return match blocks in order of appearance; with {@code includeUntracked} the two runs are
     *     merged and the whole list comes back ordered by path instead, so a file's blocks stay
     *     together rather than splitting around the seam between the runs. Empty if nothing matched
     */
    List<GitGrepMatch> grepContent(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            int contextLines,
            int maxResults,
            boolean includeUntracked) {

        int ctx = Math.min(Math.max(contextLines, 0), 10);
        int limit = Math.min(Math.max(maxResults, 1), 200);

        if (!regex && (pattern.contains(".*") || pattern.contains("|"))) {
            log.warn(
                    "grepContent: pattern '{}' looks like regex but regex=false — using literal match",
                    pattern);
        }

        String glob =
                pathGlob == null || pathGlob.isBlank()
                        ? null
                        : RepoPaths.toForwardSlashes(pathGlob.strip());
        long deadline = System.nanoTime() + timeout.toNanos();
        List<GitGrepMatch> tracked =
                GitGrep.parse(
                        exec(
                                GitGrep.args(pattern, glob, regex, ctx, null, null),
                                ctx,
                                outputLines(ctx, limit),
                                deadline),
                        ctx,
                        limit);
        // No roots left to search is not "search everywhere": without a pathspec the untracked run
        // would sweep the whole working tree.
        if (!includeUntracked || visible.allowGlobRoots().isEmpty()) {
            return tracked;
        }

        // A second, separately bounded run: `--untracked` cannot be added to the one above without
        // also dragging in every other untracked file in the repository, and
        // `--no-exclude-standard`
        // would send it through node_modules and build/. Rooting it at the globs' own directories
        // keeps the walk the size of the named area.
        // Unbounded in blocks, since the filters below decide what counts, and bounded in lines
        // by the output ceiling alone, since the roots are a named area and not the repository.
        List<GitGrepMatch> extra =
                GitGrep.parse(
                        exec(
                                GitGrep.args(
                                        pattern, null, regex, ctx, visible.allowGlobRoots(), null),
                                ctx,
                                MAX_OUTPUT_LINES,
                                deadline),
                        ctx,
                        Integer.MAX_VALUE);
        Set<String> trackedPaths = Set.copyOf(visible.trackedPaths());
        @Nullable Pathspec pathspec = Pathspec.of(glob);
        List<GitGrepMatch> merged = new ArrayList<>(tracked);
        extra.stream()
                // The roots are wider than the globs, and `--untracked` reports tracked files too;
                // `glob` is re-applied by hand because it is spent on the pathspec above.
                .filter(m -> !trackedPaths.contains(m.path()))
                .filter(m -> visible.matchesAllowGlobs(m.path()))
                .filter(m -> pathspec == null || pathspec.matches(m.path()))
                .forEach(merged::add);
        // Cut only once everything invisible is gone, or a large untracked area would spend the
        // whole cap on matches nobody gets to see.
        return merged.stream()
                .sorted(Comparator.comparing(GitGrepMatch::path))
                .limit(limit)
                .toList();
    }

    /**
     * {@link #grepContent} over the tree of a commit instead of the working tree: what the search
     * page asks for with a revision chosen. Untracked files have no place in a commit, so there is
     * no second run here.
     *
     * @param rev hash (full or short), branch, tag or {@code HEAD~2}; resolved through JGit first,
     *     so only a hash ever reaches the command line
     * @throws IllegalArgumentException if the revision is unknown or ambiguous, or the pattern is
     *     not a valid regular expression
     */
    List<GitGrepMatch> grepContentAt(
            @NonNull String rev,
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            int contextLines,
            int maxResults) {
        int ctx = Math.min(Math.max(contextLines, 0), 10);
        int limit = Math.min(Math.max(maxResults, 1), 200);
        String commit = CommitFiles.commitOf(repository, rev.strip()).name();
        String glob =
                pathGlob == null || pathGlob.isBlank()
                        ? null
                        : RepoPaths.toForwardSlashes(pathGlob.strip());
        List<String> lines =
                exec(
                        GitGrep.args(pattern, glob, regex, ctx, null, commit),
                        ctx,
                        outputLines(ctx, limit),
                        System.nanoTime() + timeout.toNanos());
        return GitGrep.parse(GitGrep.withoutCommitPrefix(lines, commit), ctx, limit);
    }

    /** Output lines that are enough for {@code limit} blocks: exact without context. */
    private static int outputLines(int ctx, int limit) {
        return ctx == 0 ? limit : MAX_OUTPUT_LINES;
    }

    /**
     * Runs {@code git grep} as a subprocess — the one operation JGit cannot do in-process — and
     * reads at most {@code maxLines} of what it prints: past that git is stopped and the lines
     * already in hand are the answer, since the caller has no use for the rest.
     *
     * <p>Exit code 1 is git's "no match" and comes back as the (empty) output. 128 is git's own
     * refusal, told apart by what it complains about: the pattern (a broken regular expression) is
     * the caller's mistake and surfaces as {@link IllegalArgumentException} carrying git's words,
     * so the caller can show why nothing came back instead of an empty list; anything else git
     * refuses — a repository it cannot read — is a failure of this side.
     *
     * @param ctx the context lines the command asks for; with none, output has no block separators
     *     and the cut falls on a block boundary by itself
     * @param deadline {@link System#nanoTime()} past which the run is killed
     * @throws IllegalArgumentException if git refused the pattern
     * @throws GitGrepTimeoutException if git did not answer by {@code deadline}
     * @throws IllegalStateException if git failed in any other way
     */
    private List<String> exec(List<String> command, int ctx, int maxLines, long deadline) {
        long budget = deadline - System.nanoTime();
        if (budget <= 0) {
            throw timedOut(command);
        }
        try {
            // core.quotepath=false: without it, git quotes/octal-escapes any path containing
            // non-ASCII bytes (e.g. Cyrillic filenames) in grep output — "docs/проект" becomes
            // "\"docs/\\320\\277...\"", which breaks path parsing.
            List<String> withConfig = new ArrayList<>(command.size() + 2);
            withConfig.add(command.get(0));
            withConfig.add("-c");
            withConfig.add("core.quotepath=false");
            withConfig.addAll(command.subList(1, command.size()));

            ProcessBuilder pb =
                    new ProcessBuilder(withConfig)
                            .directory(paths.root().toFile())
                            .redirectErrorStream(true);
            Process process = pb.start();
            // The read below blocks until git closes its output, so the deadline is kept by a
            // watchdog that kills the process; the read then ends and waitFor sees the signal.
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread watchdog =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            if (!process.waitFor(budget, TimeUnit.NANOSECONDS)) {
                                                timedOut.set(true);
                                                process.destroyForcibly();
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
            List<String> lines = new ArrayList<>();
            boolean cut = false;
            try (var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() >= maxLines) {
                        // Enough. Whatever git still has to say would be thrown away, so it is
                        // not read; the kill is what ends git, not a full pipe.
                        cut = true;
                        process.destroyForcibly();
                        break;
                    }
                }
            }
            int exit = process.waitFor();
            watchdog.interrupt();
            if (cut) {
                // Killed by this side with the answer in hand: the exit code says only that, and
                // so does the watchdog if the deadline fell on the same instant. Without context
                // every line is a block of its own and all of them stand. With context the run
                // ended inside a block, and that block is dropped — its separator is where the
                // last complete one ended, and a buffer without a separator holds no complete
                // block at all.
                if (ctx == 0) {
                    return lines;
                }
                int lastSeparator = lines.lastIndexOf("--");
                if (lastSeparator < 0) {
                    log.warn(
                            "Git command filled {} lines with one unfinished block: {}",
                            maxLines,
                            command);
                    return List.of();
                }
                return lines.subList(0, lastSeparator);
            }
            if (timedOut.get()) {
                log.warn("Git command killed after {}: {}", timeout, command);
                throw timedOut(command);
            }
            if (exit > 1) {
                String output = String.join("\n", lines);
                log.warn("Git command exited {}: {} → {}", exit, command, output);
                String badPattern = exit == 128 ? patternComplaint(lines) : null;
                if (badPattern != null) {
                    throw new IllegalArgumentException(badPattern);
                }
                throw new IllegalStateException("git grep exited " + exit + ": " + output);
            }
            // Exit 1 is git grep's "no matches" — not an error, the output is simply empty.
            return lines;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted: " + command, e);
        } catch (IOException e) {
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    private GitGrepTimeoutException timedOut(List<String> command) {
        return new GitGrepTimeoutException(
                "git grep did not finish within "
                        + timeout.toSeconds()
                        + "s: "
                        + String.join(" ", command));
    }

    /**
     * Git's complaint about the pattern, if that is what it refused: the {@code fatal:} line that
     * names the {@code -e} option, without the prefix and without the "-e option," git puts before
     * the offending regex — the pattern travels as {@code -e}'s value, so that is how git names it.
     * {@code null} when git refused something else.
     */
    private static @Nullable String patternComplaint(List<String> output) {
        return output.stream()
                .filter(line -> line.startsWith("fatal:"))
                .map(line -> line.substring("fatal:".length()).strip())
                .filter(line -> line.startsWith("-e option, "))
                .map(line -> line.substring("-e option, ".length()))
                .findFirst()
                .orElse(null);
    }
}
