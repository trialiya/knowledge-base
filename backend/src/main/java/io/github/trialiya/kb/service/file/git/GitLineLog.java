package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 */
final class GitLineLog {

    /**
     * How long one walk may run. {@code -L} diffs the range at every commit that touched the file,
     * so a long history of a busy file costs more than its blame.
     */
    static final Duration TIMEOUT = GitBlameRunner.BLAME_TIMEOUT;

    private final Repository repository;
    private final GitReadProcess git;

    GitLineLog(RepoPaths paths, Repository repository) {
        this.repository = repository;
        this.git = new GitReadProcess(
                paths.root(), TIMEOUT, " — narrow fromLine/toLine or lower maxCount: git stops at that many commits");
    }

    /**
     * The commits that changed lines {@code fromLine}…{@code toLine} of the file as it is at HEAD.
     *
     * @param normalized a path {@link RepoPaths#normalize} has cleaned, as it is at HEAD
     * @param fromLine first line (1-based, inclusive); {@code null} — the file's first
     * @param toLine last line (inclusive); {@code null} — the file's last; one past the end is
     *     pulled in, as with blame
     * @param maxCount how many commits to return; one more is asked for, so {@code truncated} is
     *     exact
     * @throws IllegalArgumentException if HEAD holds no such file, the range starts past its end or
     *     ends before it starts
     * @throws GitReadTimeoutException if git did not answer in time
     */
    GitCommitSearchResult log(
            String normalized,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            int maxCount,
            boolean includeBody) {
        if (toLine != null && toLine < Math.max(1, fromLine == null ? 1 : fromLine)) {
            throw new IllegalArgumentException("toLine " + toLine + " is before fromLine " + fromLine);
        }
        String head = CommitFiles.commitOf(repository, "HEAD").name();
        int total = RepoFiles.lineCount(
                CommitFiles.read(repository, head, normalized).bytes());
        GitBlame.Lines lines = GitBlame.fit(fromLine, toLine, total);
        if (lines == null) {
            throw new IllegalArgumentException(
                    "fromLine " + fromLine + " is past the end of " + normalized + " (" + total + " lines at HEAD)");
        }
        List<String> command = args(normalized, lines, maxCount + 1, head);
        GitReadProcess.Output out = git.run(command, maxCount + 1, git.deadline());
        out.requireExit(command, 0);
        List<String> hashes = out.lines().stream()
                .map(String::strip)
                .filter(h -> !h.isEmpty())
                .toList();
        boolean truncated = hashes.size() > maxCount;
        return new GitCommitSearchResult(
                commits(truncated ? hashes.subList(0, maxCount) : hashes, includeBody), truncated);
    }

    /**
     * {@code git log -L <from>,<to>:<path> --no-patch --format=%H -n <count> <head>}. The path goes
     * inside the {@code -L} argument — git takes no {@code --} for it — so a path starting with
     * {@code -} is never read as an option; {@code head} is a resolved hash.
     */
    static List<String> args(String path, GitBlame.Lines lines, int count, String head) {
        return List.of(
                "git",
                "log",
                "-L",
                lines.from() + "," + lines.to() + ":" + path,
                "--no-patch",
                "--format=%H",
                "-n",
                String.valueOf(count),
                head);
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
