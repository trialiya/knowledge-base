package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * The history of a range of lines — {@code git log -L}: the commits that changed those lines,
 * newest first, following them as edits above shift them and as the file is renamed. What blame
 * cannot say: blame names one commit per line, this lists every commit the lines went through.
 *
 * <p>A subprocess, like blame and grep: JGit has no line-range log. Only the hashes are read off
 * git's output; the commits themselves are described through JGit, the same way the rest of the
 * history is ({@link Diffs#toGitCommit}).
 *
 * <p>The range is numbered as the working tree has it — as the model read it with {@code
 * getFileContent} or {@code grepContent} — while {@code -L} starts from HEAD. Uncommitted edits
 * above the range would send git to other lines, so the range is carried to HEAD's numbering
 * across the diff between the two first ({@link LineShift#toOld}).
 */
final class GitLineLog {

    /**
     * How long one walk may run. {@code -L} diffs the range at every commit that touched the file,
     * so a long history of a busy file costs more than its blame.
     */
    static final Duration TIMEOUT = GitBlameRunner.BLAME_TIMEOUT;

    /** What git prints per commit under {@code --format=%H}: a SHA-1 or a SHA-256 hash. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final Repository repository;
    private final Git jgit;
    private final VisibleFiles visible;
    private final GitReadProcess git;

    GitLineLog(RepoPaths paths, Repository repository, Git jgit, VisibleFiles visible) {
        this.repository = repository;
        this.jgit = jgit;
        this.visible = visible;
        this.git = new GitReadProcess(
                paths.root(), TIMEOUT, " — narrow fromLine/toLine or lower maxCount: git stops at that many commits");
    }

    /**
     * The commits that changed lines {@code fromLine}…{@code toLine} of the file.
     *
     * <p>{@code truncated} is exact — git is asked for one commit more than {@code maxCount} — and
     * also set when the walk ended on the cut of a shallow clone ({@link ShallowClone}): the lines
     * may be older than the clone, and the oldest commit listed is then not where they came from.
     *
     * @param normalized a path {@link RepoPaths#normalize} has cleaned
     * @param fromLine first line (1-based, inclusive) as the working tree numbers it; {@code null} —
     *     the file's first
     * @param toLine last line (inclusive); {@code null} — the file's last; one past the end is
     *     pulled in, as with blame
     * @throws IllegalArgumentException if HEAD holds no such file or holds it binary, the range
     *     misses the file or ends before it starts, or every line of it is an uncommitted addition
     * @throws GitReadTimeoutException if git did not answer in time
     */
    GitCommitSearchResult log(
            String normalized,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            int maxCount,
            boolean includeBody) {
        GitBlame.requireOrdered(fromLine, toLine);
        String head = CommitFiles.commitOf(repository, "HEAD").name();
        GitBlame.Lines lines = atHead(normalized, head, fromLine, toLine);
        List<String> command = args(normalized, lines, maxCount + 1, head);
        GitReadProcess.Output out = git.run(command, maxCount + 1, git.deadline());
        out.requireExit(command, 0);
        List<String> hashes = hashes(out.lines());
        boolean more = hashes.size() > maxCount;
        List<String> shown = more ? hashes.subList(0, maxCount) : hashes;
        // Any commit listed, not only the last: past a merge the walk can stop on the cut of one
        // side and go on down the other.
        Set<String> boundaries = ShallowClone.boundaries(repository);
        boolean cut = !more && shown.stream().anyMatch(boundaries::contains);
        return new GitCommitSearchResult(commits(shown, includeBody), more || cut);
    }

    /**
     * The asked range in HEAD's numbering. The working-tree file is read only when git sees it
     * changed against HEAD; unchanged or deleted, the numbers are already HEAD's.
     */
    private GitBlame.Lines atHead(
            String normalized, String head, @Nullable Integer fromLine, @Nullable Integer toLine) {
        byte[] committed = CommitFiles.read(repository, head, normalized).bytes();
        if (RepoFiles.isBinary(committed)) {
            throw new IllegalArgumentException("Binary file has no line history: " + normalized);
        }
        byte[] work = workingCopy(normalized);
        byte[] numbered = work == null ? committed : work;
        int total = RepoFiles.lineCount(numbered);
        GitBlame.Lines asked = GitBlame.fit(fromLine, toLine, total);
        if (asked == null) {
            throw new IllegalArgumentException("Lines " + (fromLine == null ? 1 : fromLine) + "–"
                    + (toLine == null ? "end" : toLine) + " are not in " + normalized + ": it has " + total
                    + " lines");
        }
        if (work == null) {
            return asked;
        }
        // Trailing whitespace aside: a working copy checked out with CRLF must not read as every
        // line edited.
        GitBlame.Lines carried = LineShift.toOld(
                LineShift.diff(RawTextComparator.WS_IGNORE_TRAILING, new RawText(committed), new RawText(work)), asked);
        if (carried == null) {
            throw new IllegalArgumentException("Lines " + asked.from() + "–" + asked.to() + " of " + normalized
                    + " are uncommitted additions: they have no history yet");
        }
        return carried;
    }

    /**
     * The file as the working tree has it, when its numbering can differ from HEAD's; {@code null}
     * otherwise. Whether it changed is git's status, not a byte comparison: status reads through
     * the checkout filters (line endings, LFS), so a clean file is never diffed against a blob it
     * only looks unlike — and is not read at all, which is the common case.
     */
    private byte @Nullable [] workingCopy(String normalized) {
        if (!changedAgainstHead(normalized)) {
            return null;
        }
        // Refused like blame refuses it: numbering a changed file by HEAD would follow other lines.
        VisibleFiles.Resolved resolved = visible.require(normalized);
        // Changed in the working tree or the index, so tracked; deleted, nothing to number.
        if (!Files.exists(resolved.absolute(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return RepoFiles.workingText(normalized, resolved);
    }

    /**
     * Whether the working tree or the index holds the file other than HEAD does — a conflicted file
     * too: its working copy, markers and all, is what the model read.
     */
    private boolean changedAgainstHead(String normalized) {
        try {
            Status status = jgit.status().addPath(normalized).call();
            return status.getModified().contains(normalized)
                    || status.getChanged().contains(normalized)
                    || status.getConflicting().contains(normalized);
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot read the status of " + normalized, e);
        }
    }

    /**
     * {@code git log -L <from>,<to>:<path> --no-patch --no-show-signature --format=%H -n <count>
     * <head>}. The path goes inside the {@code -L} argument — git takes no {@code --} for it — so a
     * path starting with {@code -} is never read as an option; {@code head} is a resolved hash.
     * {@code --no-show-signature} overrides a {@code log.showSignature} in the host's config, which
     * would print signature lines between the hashes.
     */
    static List<String> args(String path, GitBlame.Lines lines, int count, String head) {
        return List.of(
                "git",
                "log",
                "-L",
                lines.from() + "," + lines.to() + ":" + path,
                "--no-patch",
                "--no-show-signature",
                "--format=%H",
                "-n",
                String.valueOf(count),
                head);
    }

    /**
     * The hashes, one per line. Anything else is refused rather than skipped: a git that prints the
     * patch despite {@code --no-patch} would fill the read with diff lines, and the answer would be
     * short without saying so.
     */
    static List<String> hashes(List<String> lines) {
        List<String> hashes = new ArrayList<>(lines.size());
        for (String line : lines) {
            String hash = line.strip();
            if (hash.isEmpty()) {
                continue;
            }
            if (!HASH.matcher(hash).matches()) {
                throw new IllegalStateException("Unexpected git log -L output: " + hash);
            }
            hashes.add(hash);
        }
        return hashes;
    }

    private List<GitCommit> commits(List<String> hashes, boolean includeBody) {
        List<GitCommit> commits = new ArrayList<>(hashes.size());
        try (RevWalk walk = new RevWalk(repository)) {
            for (String hash : hashes) {
                commits.add(Diffs.toGitCommit(
                        walk.parseCommit(ObjectId.fromString(hash)), null, walk.getObjectReader(), includeBody));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the commits of a line range", e);
        }
        return commits;
    }
}
