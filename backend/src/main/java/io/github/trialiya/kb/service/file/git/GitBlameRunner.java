package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
     * @throws IllegalArgumentException if the path is not served, is not tracked (blame needs
     *     history, and an untracked file has none), or is binary
     * @throws GitReadTimeoutException if git did not answer in time
     */
    GitFileBlame blame(String normalized) {
        VisibleFiles.Resolved resolved = visible.require(normalized);
        if (!resolved.tracked()) {
            throw new IllegalArgumentException("File is not tracked: " + normalized);
        }
        byte[] head = RepoFiles.readWindow(normalized, resolved.absolute(), 0, RepoFiles.BINARY_SNIFF_BYTES);
        requireText(normalized, head);
        List<String> ignored = GitBlame.ignoredRevs(workingTreeIgnoreFile());
        return run(normalized, ignored, null);
    }

    /**
     * Authorship of the file as of a commit. The ignore list is the commit's own {@value
     * #IGNORE_REVS_FILE}, not the working tree's: the snapshot is what it says it is.
     *
     * @param rev hash (full or short), branch, tag or {@code HEAD~2}; resolved through JGit first,
     *     so only a hash ever reaches the command line
     * @throws IllegalArgumentException if the revision is unknown or ambiguous, the commit holds no
     *     file at that path, or the file is binary
     */
    GitFileBlame blameAt(String rev, String normalized) {
        String commit = CommitFiles.commitOf(repository, rev.strip()).name();
        CommitFiles.Blob blob = CommitFiles.read(repository, commit, normalized);
        requireText(normalized, blob.bytes());
        List<String> ignored = GitBlame.ignoredRevs(snapshotIgnoreFile(commit));
        return run(normalized, ignored, commit);
    }

    private GitFileBlame run(String normalized, List<String> ignored, @Nullable String commit) {
        List<String> command = GitBlame.args(normalized, ignored, commit);
        GitReadProcess.Output out = git.run(command, MAX_OUTPUT_LINES, git.deadline());
        if (out.cut()) {
            log.warn("git blame filled {} lines: {}", MAX_OUTPUT_LINES, command);
            throw new IllegalStateException("git blame output too large for " + normalized);
        }
        if (out.exit() != 0) {
            String said = out.said();
            log.warn("Git command exited {}: {} → {}", out.exit(), command, said);
            throw new IllegalStateException("git blame exited " + out.exit() + ": " + said);
        }
        List<GitFileBlame.Hunk> hunks = GitBlame.parse(out.lines());
        int lineCount = hunks.stream().mapToInt(GitFileBlame.Hunk::lineCount).sum();
        return new GitFileBlame(normalized, commit, lineCount, hunks);
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
