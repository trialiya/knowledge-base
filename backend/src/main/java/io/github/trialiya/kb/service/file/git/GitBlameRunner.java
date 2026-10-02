package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Line authorship of one file: the {@code git blame} subprocess and its command line ({@link
 * GitBlame}). JGit has a blame of its own, but it cannot skip the revisions {@code
 * .git-blame-ignore-revs} names — and a column that credits every line to the last reformatting
 * commit says nothing — so this one leaves the JVM through {@link GitReadProcess}, like grep.
 */
@Slf4j
final class GitBlameRunner {

    /**
     * The file whose revisions blame skips: the same one GitHub's blame view and {@code git config
     * blame.ignoreRevsFile} read, so the column here agrees with both.
     */
    static final String IGNORE_REVS_FILE = ".git-blame-ignore-revs";

    /**
     * How long one blame may run. The walk is over one file's history, and a file that takes longer
     * than this is better answered with "not available right now" than left to hold the repository.
     */
    static final Duration BLAME_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Output lines one run is read up to. Porcelain prints one header per line of the file plus a
     * dozen fields per commit; a file the browser serves whole ({@link RepoFiles#MAX_FILE_SIZE})
     * fits many times over. A run that reaches the cap is refused, not cut: a column that stops
     * halfway down the file is not an answer.
     */
    static final int MAX_OUTPUT_LINES = 400_000;

    /**
     * The largest ignore file read. It lists hashes, a line each; anything bigger is not that file.
     */
    private static final long MAX_IGNORE_FILE_BYTES = 64 * 1024;

    private final RepoPaths paths;
    private final Repository repository;
    private final VisibleFiles visible;
    private final GitReadProcess git;

    GitBlameRunner(RepoPaths paths, Repository repository, VisibleFiles visible) {
        this(paths, repository, visible, BLAME_TIMEOUT);
    }

    /** With the deadline spelled out — a test's way of seeing one expire. */
    GitBlameRunner(RepoPaths paths, Repository repository, VisibleFiles visible, Duration timeout) {
        this.paths = paths;
        this.repository = repository;
        this.visible = visible;
        this.git = new GitReadProcess(paths.root(), timeout);
    }

    /**
     * Authorship of the file as it is in the working tree: an uncommitted edit shows as a hunk with
     * no commit. The ignore list is the working tree's copy of {@value #IGNORE_REVS_FILE}.
     *
     * @param normalized a path {@link RepoPaths#normalize} has cleaned
     * @param range the lines to blame; {@code null} — the whole file
     * @throws IllegalArgumentException if the path is not served, is not tracked (blame needs
     *     history, and an untracked file has none), or is binary
     * @throws GitReadTimeoutException if git did not answer in time
     */
    GitFileBlame blame(String normalized, @Nullable Range range) {
        Target target = workingTree(normalized);
        int total = range == null ? 0 : target.count().getAsInt();
        return run(normalized, target.ignored().get(), null, range, total, target.count());
    }

    /**
     * Authorship of the file as of a commit. The ignore list is the commit's own {@value
     * #IGNORE_REVS_FILE}, not the working tree's: the snapshot is what it says it is.
     *
     * @param rev hash (full or short), branch, tag or {@code HEAD~2}; resolved through JGit first,
     *     so only a hash ever reaches the command line
     * @param range the lines to blame; {@code null} — the whole file
     * @throws IllegalArgumentException if the revision is unknown or ambiguous, the commit holds no
     *     file at that path, or the file is binary
     */
    GitFileBlame blameAt(String rev, String normalized, @Nullable Range range) {
        Target target = snapshot(rev, normalized);
        int total = range == null ? 0 : target.count().getAsInt();
        return run(normalized, target.ignored().get(), target.commit(), range, total, null);
    }

    /**
     * What a blame of one file starts from: the commit it is read at ({@code null} — the working
     * tree), the revisions it skips and the file's line count as git counts it — both read only
     * when asked for: the trace of a line's origin skips no revisions of the ignore file.
     */
    private record Target(@Nullable String commit, Supplier<List<String>> ignored, IntSupplier count) {}

    /** The working-tree file, refused unless git has history for it and it is text. */
    private Target workingTree(String normalized) {
        VisibleFiles.Resolved resolved = visible.require(normalized);
        if (!resolved.tracked()) {
            throw new IllegalArgumentException("File is not tracked: " + normalized);
        }
        // A tracked file whose repository has no commit yet (a staged file on an unborn branch)
        // is git's `fatal: no such ref: HEAD` — the caller's situation, not a failure here.
        if (CommitFiles.commitOrNull(repository, "HEAD") == null) {
            throw new IllegalArgumentException("Repository has no commits yet: " + normalized);
        }
        byte[] head = RepoFiles.readWindow(normalized, resolved.absolute(), 0, RepoFiles.BINARY_SNIFF_BYTES);
        requireText(normalized, head);
        return new Target(
                null,
                () -> resolvable(GitBlame.ignoredRevs(workingTreeIgnoreFile())),
                () -> RepoFiles.lineCount(normalized, resolved.absolute()));
    }

    /** The file as of a commit, refused unless the commit holds it and it is text. */
    private Target snapshot(String rev, String normalized) {
        String commit = CommitFiles.commitOf(repository, rev.strip()).name();
        CommitFiles.Blob blob = CommitFiles.read(repository, commit, normalized);
        requireText(normalized, blob.bytes());
        int total = RepoFiles.lineCount(blob.bytes());
        return new Target(commit, () -> resolvable(GitBlame.ignoredRevs(snapshotIgnoreFile(commit))), () -> total);
    }

    /**
     * Where {@code query} entered line {@code line} of the file: the line is blamed, the version
     * of it in the commit blamed is checked for the substring, and while it is there the line is
     * blamed again with that commit skipped ({@code --ignore-rev}) — git then credits the line to
     * the version of it before that commit, matched by similarity. The last version that still
     * has the substring is where it entered.
     *
     * <p>The walk ends when a version no longer has the substring (it entered in the version
     * after: {@code before} is that version), or when skipping a commit gives the same commit back
     * — git could not pass it, because the line was added there or the file was created there.
     * Lines are followed across renames, and with {@code -C} into files the same commit changed,
     * so code moved into a new file is followed back to where it was written.
     *
     * <p>The match by similarity is git's heuristic: when the line was added, git may still pair
     * it with a neighbour, and the check for the substring in the version it names is what stops
     * the walk there — unless the neighbour has the substring too.
     *
     * @param rev the commit to start at; {@code null} — the working tree
     * @param line 1-based line of the file as of {@code rev}
     * @throws IllegalArgumentException as {@link #blame} and {@link #blameAt}
     * @throws GitReadTimeoutException if the walk did not finish within one blame's deadline
     */
    GitLineOrigin origin(String normalized, @Nullable String rev, int line, String query) {
        Target target = rev == null ? workingTree(normalized) : snapshot(rev, normalized);
        String commit = target.commit();
        if (line > target.count().getAsInt()) {
            return answer(normalized, line, query, commit, Status.NOT_IN_LINE, List.of(), null);
        }
        Pattern needle = LineOrigin.needle(query);
        // The ignore file is not used here: a reformatting commit that did not touch the substring
        // is walked past as one more step, and one that brought it in is the answer — skipping it
        // would credit the line to a version without the substring and lose the find.
        Set<String> walked = new LinkedHashSet<>();
        List<GitLineOrigin.Step> steps = new ArrayList<>();
        long deadline = git.deadline();
        while (true) {
            GitFileBlame.Hunk hunk;
            try {
                hunk = blameLine(normalized, List.copyOf(walked), line, commit, deadline);
            } catch (GitReadTimeoutException e) {
                // The versions already walked are an answer of their own — the oldest reached, as
                // at the step limit; only a walk that got nowhere is the timeout it is.
                if (steps.isEmpty()) {
                    throw e;
                }
                return answer(normalized, line, query, commit, Status.LIMIT, steps, null);
            }
            String hash = hunk.hash();
            if (hash == null) {
                return answer(normalized, line, query, commit, Status.UNCOMMITTED, List.of(), null);
            }
            if (walked.contains(hash)) {
                return answer(normalized, line, query, commit, stuckAt(hash), steps, null);
            }
            // Checked only now, after the blame: the last step allowed may well be the origin,
            // and only the blame after it can say so.
            if (steps.size() == LineOrigin.MAX_STEPS) {
                return answer(normalized, line, query, commit, Status.LIMIT, steps, null);
            }
            String path = hunk.path() == null ? normalized : hunk.path();
            int at = hunk.sourceLine() == null ? line : hunk.sourceLine();
            String text =
                    LineOrigin.lineAt(CommitFiles.read(repository, hash, path).bytes(), at);
            GitLineOrigin.Step step = LineOrigin.step(hunk, hash, path, at, text == null ? "" : text);
            if (text == null || !needle.matcher(text).find()) {
                // The first version is the line as it is now: no substring there means the file
                // changed after the search, not a history to walk.
                Status status = steps.isEmpty() ? Status.NOT_IN_LINE : Status.FOUND;
                return answer(normalized, line, query, commit, status, steps, steps.isEmpty() ? null : step);
            }
            steps.add(step);
            walked.add(hash);
        }
    }

    private static GitLineOrigin answer(
            String normalized,
            int line,
            String query,
            @Nullable String commit,
            Status status,
            List<GitLineOrigin.Step> steps,
            GitLineOrigin.@Nullable Step before) {
        return new GitLineOrigin(normalized, line, query, commit, status, List.copyOf(steps), before);
    }

    /**
     * The walk stopped on a commit git gave back when asked to skip it: git paired the line with
     * nothing older — it was added there, or rewritten past what git's similarity match pairs.
     * Git cannot pass the first commit of a shallow clone either, but there the line may be older
     * than the clone — that is where history ends, not where the substring appeared.
     */
    private Status stuckAt(String hash) {
        return shallowBoundary(hash) ? Status.BOUNDARY : Status.FOUND;
    }

    /**
     * Whether the commit is where this clone's history was cut ({@code shallow} in the common git
     * directory names it — not the worktree's own, which a linked worktree has apart).
     */
    private boolean shallowBoundary(String hash) {
        Path shallow = repository.getCommonDirectory().toPath().resolve("shallow");
        if (!Files.isRegularFile(shallow)) {
            return false;
        }
        try {
            return Files.readAllLines(shallow, StandardCharsets.US_ASCII).stream()
                    .anyMatch(hash::equals);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + shallow, e);
        }
    }

    /** One step of the walk ({@link GitBlame#args} with {@code trace}), on its shared deadline. */
    private GitFileBlame.Hunk blameLine(
            String normalized, List<String> ignored, int line, @Nullable String commit, long deadline) {
        List<String> command = GitBlame.args(normalized, ignored, new GitBlame.Lines(line, line), commit, true);
        GitReadProcess.Output out = git.run(command, MAX_OUTPUT_LINES, deadline);
        out.requireExit(command, 0);
        List<GitFileBlame.Hunk> hunks = GitBlame.parse(out.lines());
        if (hunks.isEmpty()) {
            throw new IllegalStateException("git blame named no commit for " + normalized + ":" + line);
        }
        return hunks.getFirst();
    }

    /**
     * The lines asked about, before they are fitted to the file: either bound may be missing — the
     * file's own edge — or lie outside it.
     */
    record Range(@Nullable Integer fromLine, @Nullable Integer toLine) {}

    /**
     * Only the revisions this repository can name: git refuses the whole run on an {@code
     * --ignore-rev} it cannot find, and a shallow or partial clone — or a history rewritten since
     * the file was written — does not hold every hash the file lists. A commit not in the clone
     * cannot be blamed for a line anyway, so leaving it out changes nothing in the answer.
     */
    private List<String> resolvable(List<String> revs) {
        return revs.stream()
                .filter(rev -> {
                    // Missing, not a commit, an ambiguous abbreviation, an unreadable object: all
                    // absent — git takes only a commit for --ignore-rev.
                    boolean known = CommitFiles.commitOrNull(repository, rev) != null;
                    if (!known) {
                        log.debug("Cannot resolve {} as a commit in {}", rev, paths.root());
                    }
                    return known;
                })
                .toList();
    }

    /**
     * @param range the lines asked about, or {@code null} for the whole file
     * @param total the file's line count as git counts it; read only when {@code range} is set —
     *     the whole file's count is the sum of its hunks, and costs no second read
     * @param recount for the working tree, a fresh count of its lines; {@code null} for a snapshot,
     *     whose count cannot change
     */
    private GitFileBlame run(
            String normalized,
            List<String> ignored,
            @Nullable String commit,
            @Nullable Range range,
            int total,
            @Nullable IntSupplier recount) {
        if (range == null) {
            List<String> command = GitBlame.args(normalized, ignored, null, commit);
            GitReadProcess.Output out = exec(command, normalized);
            out.requireExit(command, 0);
            List<GitFileBlame.Hunk> hunks = GitBlame.parse(out.lines());
            int lineCount =
                    hunks.stream().mapToInt(GitFileBlame.Hunk::lineCount).sum();
            return new GitFileBlame(normalized, commit, lineCount, hunks, null, null);
        }
        return ranged(normalized, ignored, commit, range, total, recount);
    }

    /**
     * @param recount asked once when git refuses: the working-tree file can get shorter between
     *     counting its lines and git reading it — an edit tool writes into the same repository —
     *     and a range fitted to the old length then starts past the new end. A shorter recount
     *     fits the range again and asks once more; anything else is git's refusal as it stands.
     *     The refusal's own text is not read for the length: git translates it
     */
    private GitFileBlame ranged(
            String normalized,
            List<String> ignored,
            @Nullable String commit,
            Range range,
            int total,
            @Nullable IntSupplier recount) {
        GitBlame.Lines lines = GitBlame.fit(range.fromLine(), range.toLine(), total);
        if (lines == null) {
            // The range starts past the file's end (GitService refuses a reversed one): git would
            // refuse it outright, and an empty answer naming the file's length says more.
            int from = range.fromLine() == null ? 1 : Math.max(1, range.fromLine());
            return new GitFileBlame(normalized, commit, total, List.of(), from, from - 1);
        }
        List<String> command = GitBlame.args(normalized, ignored, lines, commit);
        GitReadProcess.Output out = exec(command, normalized);
        if (out.exit() != 0 && recount != null) {
            int now = recount.getAsInt();
            if (now < total) {
                return ranged(normalized, ignored, commit, range, now, null);
            }
        }
        out.requireExit(command, 0);
        return new GitFileBlame(normalized, commit, total, GitBlame.parse(out.lines()), lines.from(), lines.to());
    }

    /** One run, refused when it fills the output ceiling; the exit code is the caller's to read. */
    private GitReadProcess.Output exec(List<String> command, String normalized) {
        GitReadProcess.Output out = git.run(command, MAX_OUTPUT_LINES, git.deadline());
        if (out.cut()) {
            log.warn("git blame filled {} lines: {}", MAX_OUTPUT_LINES, command);
            throw new IllegalStateException("git blame output too large for " + normalized);
        }
        return out;
    }

    private static void requireText(String normalized, byte[] bytes) {
        if (RepoFiles.isBinary(bytes)) {
            throw new IllegalArgumentException("Binary file has no line authorship: " + normalized);
        }
    }

    private String workingTreeIgnoreFile() {
        Path file = paths.resolve(IGNORE_REVS_FILE);
        if (!Files.isRegularFile(file)) {
            return "";
        }
        byte[] bytes = RepoFiles.readWindow(IGNORE_REVS_FILE, file, 0, (int) MAX_IGNORE_FILE_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String snapshotIgnoreFile(String commit) {
        try (CommitFiles.Commit at = CommitFiles.Commit.open(repository, commit)) {
            CommitFiles.Entry entry = at.entry(IGNORE_REVS_FILE);
            if (entry.kind() != CommitFiles.Kind.FILE) {
                return "";
            }
            return new String(
                    at.blob(entry, IGNORE_REVS_FILE, MAX_IGNORE_FILE_BYTES).bytes(), StandardCharsets.UTF_8);
        }
    }
}
