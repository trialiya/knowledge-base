package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin.Status;
import io.github.trialiya.kb.service.file.git.FileVersions.At;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * Where a substring entered a line: back through the versions of that line, one step per commit
 * that changed it, while the version still has the substring. In process, through JGit — no {@code
 * git} subprocess, so nothing in the repository's git config or the host's locale bends the
 * answer.
 *
 * <p>One step is two moves. A plain blame of the line names the commit that last changed it and
 * the line's place in that commit's file (renames followed). Then the line is carried into that
 * commit's parent by a diff of the file between the two, and this is where the substring decides:
 *
 * <ul>
 *   <li>the line sits in a hunk that replaced old lines — among those, the one that has the
 *       substring (the most like the line, when several do) is the version before, and the walk
 *       goes on from it;
 *   <li>otherwise — the hunk only inserted lines, the file is new, or none of the old lines has
 *       the substring — the same commit may have deleted the same block elsewhere, in another file
 *       or another place in this one: a move ({@link MovedBlocks}), and the walk goes on from
 *       there;
 *   <li>failing that, the substring entered with this commit: the old line most like it, when the
 *       hunk replaced any, is the answer's {@code before}; none — the commit added the line.
 * </ul>
 *
 * <p>Pairing by the substring rather than by position or similarity alone is what keeps a line
 * added beside a look-alike from being taken for that look-alike's later version — the trap both
 * {@code git blame --ignore-rev} (by similarity) and JGit's own ignore-revs (by position) fall into.
 *
 * <p>The repository's {@code .git-blame-ignore-revs} plays no part: a reformatting commit that did
 * not touch the substring is walked past as one more step, and one that brought it in is the
 * answer.
 */
final class LineOriginTracer {

    /** One deadline for the whole walk — the same budget a single {@code git blame} gets. */
    static final Duration TIMEOUT = GitBlameRunner.BLAME_TIMEOUT;

    /**
     * Walks of one repository running at once. A walk the caller stopped waiting for runs on until
     * its own next look at the deadline (see {@link #origin}); the bound keeps such walks from
     * piling up — a request past it waits for a free one within its own deadline, or times out.
     */
    static final int MAX_WALKS = 4;

    private final Repository repository;
    private final VisibleFiles visible;
    private final Duration timeout;
    private final FileVersions versions;
    private final MovedBlocks moves;
    private final Semaphore walks = new Semaphore(MAX_WALKS);

    LineOriginTracer(Repository repository, VisibleFiles visible) {
        this(repository, visible, TIMEOUT);
    }

    /** With the deadline spelled out — a test's way of seeing one expire. */
    LineOriginTracer(Repository repository, VisibleFiles visible, Duration timeout) {
        this.repository = repository;
        this.visible = visible;
        this.timeout = timeout;
        this.versions = new FileVersions(repository);
        this.moves = new MovedBlocks(repository, versions);
    }

    /** A version of the line: the commit that last changed it, where it sits there, and its text. */
    private record Version(RevCommit commit, String path, int line, String text) {}

    /** Where the walk goes from a version: on, to the version before; or it stops. */
    private sealed interface Next {
        /** The version before still has the substring: the walk goes on from it. */
        record Carried(At at) implements Next {}

        /** The version before has no substring: it entered here, and this is what it was. */
        record Without(At at, String text) implements Next {}

        /** No version before: the line was added here, or history ends here. */
        record None() implements Next {}
    }

    /**
     * @param rev the commit to start at; {@code null} — the working tree
     * @param line 1-based line of the file as of {@code rev}
     * @throws IllegalArgumentException if the file is not tracked, is binary or too large, the
     *     revision is unknown, or the repository has no commits
     * @throws GitReadTimeoutException if the walk took no step before its deadline
     */
    // PreserveStackTrace: a failure of the walk is rethrown as the walk threw it — its own trace is
    // the one that matters; the ExecutionException around it only adds this waiting frame.
    @SuppressWarnings("PMD.PreserveStackTrace")
    GitLineOrigin origin(String normalized, @Nullable String rev, int line, String query) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AtomicReference<@Nullable Answer> progress = new AtomicReference<>();
        try {
            if (!walks.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw timedOut(normalized);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted tracing " + normalized + ":" + line, e);
        }
        // The walk runs on a thread of its own and is waited for no longer than the deadline: a
        // single step of JGit's blame can walk a long history before it returns, and checks of the
        // deadline between steps would not cut it short. The thread is not interrupted — JGit
        // reads packs through channels that an interrupt closes for the whole repository — but
        // left to stop at its own next check, its late answer dropped and its permit given back.
        CompletableFuture<GitLineOrigin> result = new CompletableFuture<>();
        Thread.ofVirtual().name("line-origin").start(() -> {
            try {
                result.complete(trace(normalized, rev, line, query, deadline, progress));
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
            } finally {
                walks.release();
            }
        });
        try {
            return result.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return late(normalized, progress.get(), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot trace " + normalized + ":" + line, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted tracing " + normalized + ":" + line, e);
        }
    }

    /**
     * The answer once the caller stopped waiting: the one the walk had settled on, if it was only
     * filling in the version before; else the versions walked so far ({@link Status#LIMIT}); else
     * — nothing walked — the timeout it is.
     */
    private GitLineOrigin late(String normalized, @Nullable Answer answer, TimeoutException cause) {
        if (answer != null && answer.settled() != null) {
            return answer.status(answer.settled());
        }
        if (answer == null || answer.walked() == 0) {
            GitReadTimeoutException timeout = timedOut(normalized);
            timeout.initCause(cause);
            throw timeout;
        }
        return answer.status(Status.LIMIT);
    }

    /**
     * The walk itself, on the walk's own thread.
     *
     * @param progress where the answer being built is published, so a caller that stopped waiting
     *     can still hand out what the walk found so far
     */
    private GitLineOrigin trace(
            String normalized,
            @Nullable String rev,
            int line,
            String query,
            long deadline,
            AtomicReference<@Nullable Answer> progress) {
        Pattern needle = LineOrigin.needle(query);
        try (RevWalk walk = new RevWalk(repository)) {
            Start start =
                    rev == null ? workingTree(walk, normalized, line, needle) : snapshot(walk, rev, normalized, line);
            Answer answer = new Answer(normalized, line, query, start.commit());
            progress.set(answer);
            if (start.text() == null || !needle.matcher(start.text()).find()) {
                return answer.status(Status.NOT_IN_LINE);
            }
            At first = start.at();
            if (first == null) {
                return settleWithBefore(walk, answer, Status.UNCOMMITTED, start.before(), start.beforeText(), deadline);
            }
            return walkFrom(walk, first, needle, deadline, answer);
        } catch (FileVersions.Unreadable e) {
            // Only the start can get here — the walk itself stops on an unreadable version: the
            // file as asked about is readable, its HEAD version is not.
            throw new IllegalArgumentException("File history cannot be read as text: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot trace " + normalized + ":" + line, e);
        }
    }

    /**
     * Where the walk starts.
     *
     * @param commit the full hash of the snapshot asked about; {@code null} — the working tree
     * @param at the line in the first commit to blame; {@code null} when the substring entered with
     *     an uncommitted edit (or the line is past the end)
     * @param text the line as asked about; {@code null} past the end of the file
     * @param before for a substring that entered with an uncommitted edit, the line in HEAD it
     *     replaced, and its text; {@code null} when the edit added the line
     */
    private record Start(
            @Nullable String commit,
            @Nullable At at,
            @Nullable String text,
            @Nullable At before,
            @Nullable String beforeText) {

        static Start walk(@Nullable String commit, At at, String text) {
            return new Start(commit, at, text, null, null);
        }

        static Start uncommitted(String text, @Nullable At before, @Nullable String beforeText) {
            return new Start(null, null, text, before, beforeText);
        }

        static Start pastTheEnd(@Nullable String commit) {
            return new Start(commit, null, null, null, null);
        }
    }

    private Start snapshot(RevWalk walk, String rev, String normalized, int line) throws IOException {
        CommitFiles.Blob blob = CommitFiles.read(repository, rev.strip(), normalized);
        if (RepoFiles.isBinary(blob.bytes())) {
            throw new IllegalArgumentException("Binary file has no line history: " + normalized);
        }
        RawText file = new RawText(blob.bytes());
        int index = line - 1;
        if (index >= file.size()) {
            return Start.pastTheEnd(blob.commit());
        }
        RevCommit commit = walk.parseCommit(ObjectId.fromString(blob.commit()));
        return Start.walk(blob.commit(), new At(commit, normalized, index), FileVersions.lineOf(file, index));
    }

    /**
     * The working-tree line, carried into HEAD the way {@link #previous} carries a line into a
     * parent — the working tree is one more version on top of history: an unchanged line goes
     * through as it is; an edited one through the old line of its hunk that has the substring; one
     * the edit added, or whose committed version has no substring, is where the substring entered
     * — uncommitted.
     */
    private Start workingTree(RevWalk walk, String normalized, int line, Pattern needle) throws IOException {
        VisibleFiles.Resolved resolved = visible.require(normalized);
        if (!resolved.tracked()) {
            throw new IllegalArgumentException("File is not tracked: " + normalized);
        }
        ObjectId head = CommitFiles.commitOrNull(repository, "HEAD");
        if (head == null) {
            throw new IllegalArgumentException("Repository has no commits yet: " + normalized);
        }
        if (RepoFiles.sizeOf(normalized, resolved.absolute()) > CommitFiles.MAX_BLOB_SIZE) {
            throw new IllegalArgumentException("File too large to trace: " + normalized);
        }
        byte[] bytes = RepoFiles.readAll(normalized, resolved.absolute());
        if (RepoFiles.isBinary(bytes)) {
            throw new IllegalArgumentException("Binary file has no line history: " + normalized);
        }
        RawText work = new RawText(bytes);
        int index = line - 1;
        if (index >= work.size()) {
            return Start.pastTheEnd(null);
        }
        String text = FileVersions.lineOf(work, index);
        RevCommit headCommit = walk.parseCommit(head);
        RawText committed = versions.text(headCommit, normalized);
        if (committed == null) {
            // Tracked but not in HEAD: staged and never committed, so is every line of it.
            return Start.uncommitted(text, null, null);
        }
        // Trailing whitespace aside: a working copy checked out with CRLF must not read as every
        // line edited.
        EditList edits = diff(RawTextComparator.WS_IGNORE_TRAILING, committed, work);
        Edit edit = editAt(edits, index);
        int carried;
        if (edit == null) {
            carried = unchanged(edits, index);
            String was = FileVersions.lineOf(committed, carried);
            if (!needle.matcher(was).find()) {
                // Equal but for trailing whitespace — and the substring is in what was added.
                return Start.uncommitted(text, new At(headCommit, normalized, carried), was);
            }
        } else if (edit.getLengthA() == 0) {
            return Start.uncommitted(text, null, null);
        } else {
            carried = LineOrigin.carrying(committed, edit.getBeginA(), edit.getEndA(), needle, text);
            if (carried < 0) {
                int closest = LineOrigin.closest(committed, edit.getBeginA(), edit.getEndA(), text);
                return Start.uncommitted(
                        text, new At(headCommit, normalized, closest), FileVersions.lineOf(committed, closest));
            }
        }
        return Start.walk(null, new At(headCommit, normalized, carried), text);
    }

    private GitLineOrigin walkFrom(RevWalk walk, At first, Pattern needle, long deadline, Answer answer)
            throws IOException {
        At at = first;
        while (true) {
            Version version;
            try {
                version = blame(walk, at, deadline);
            } catch (GitReadTimeoutException e) {
                // The versions already walked are an answer of their own — the oldest reached, as
                // at the step limit; only a walk that got nowhere is the timeout it is.
                if (answer.walked() == 0) {
                    throw e;
                }
                return answer.status(Status.LIMIT);
            }
            if (!needle.matcher(version.text()).find()) {
                // Only the first version can lack it — every later one was chosen for having it:
                // the file changed after the search.
                return answer.status(Status.NOT_IN_LINE);
            }
            // Checked only now: the last step allowed may well be the origin, and only its own
            // look at the parent can say so.
            if (answer.walked() == LineOrigin.MAX_STEPS) {
                return answer.status(Status.LIMIT);
            }
            answer.step(step(version));
            Next next;
            try {
                next = previous(walk, version, needle, deadline);
            } catch (GitReadTimeoutException | FileVersions.Unreadable e) {
                // Out of time, or a version of the file cannot be read as lines (past the size cap,
                // or binary then): the oldest version reached is as far as it goes.
                return answer.status(Status.LIMIT);
            }
            switch (next) {
                case Next.Carried carried -> at = carried.at();
                case Next.Without without -> {
                    return settleWithBefore(walk, answer, Status.FOUND, without.at(), without.text(), deadline);
                }
                case Next.None() -> {
                    return answer.status(shallowBoundary(version.commit()) ? Status.BOUNDARY : Status.FOUND);
                }
            }
        }
    }

    /**
     * The answer once the origin is known and the version before it is to be named. That version
     * is named at once by where it sits — the line in the parent, as read — and the walk settles
     * on its status, so a caller that stops waiting now still gets the origin; then a blame of
     * that line, time allowing, names the commit that last changed it.
     */
    private GitLineOrigin settleWithBefore(
            RevWalk walk, Answer answer, Status status, @Nullable At before, @Nullable String text, long deadline)
            throws IOException {
        if (before != null && text != null) {
            answer.before(new GitLineOrigin.Step(
                    before.commit().name(), null, null, null, before.path(), before.line() + 1, LineOrigin.cap(text)));
        }
        answer.settle(status);
        if (before != null) {
            try {
                answer.before(step(blame(walk, before, deadline)));
            } catch (GitReadTimeoutException e) {
                // The version stays named by its place in the parent; only its own commit is left out.
                return answer.status(status);
            }
        }
        return answer.status(status);
    }

    /**
     * The commit that last changed the line, and the line's place and text there — a blame that
     * stops as soon as the line is accounted for, not one of the whole file.
     */
    private Version blame(RevWalk walk, At at, long deadline) throws IOException {
        try (BlameGenerator blame = new BlameGenerator(repository, at.path())) {
            blame.setFollowFileRenames(true);
            blame.push(null, at.commit());
            while (blame.next()) {
                checkDeadline(deadline, at.path());
                if (blame.getResultStart() <= at.line() && at.line() < blame.getResultEnd()) {
                    int line = blame.getSourceStart() + (at.line() - blame.getResultStart());
                    RevCommit commit = walk.parseCommit(blame.getSourceCommit());
                    return new Version(
                            commit, blame.getSourcePath(), line, FileVersions.lineOf(blame.getSourceContents(), line));
                }
            }
        }
        throw new IllegalStateException("Blame named no commit for " + at.path() + ":" + (at.line() + 1));
    }

    /**
     * The version of the line before {@code version}'s commit: through each parent's diff of the
     * file in turn (a merge has more than one), then through a block moved in from elsewhere.
     */
    private Next previous(RevWalk walk, Version version, Pattern needle, long deadline) throws IOException {
        RevCommit commit = version.commit();
        RawText now = versions.text(commit, version.path());
        if (now == null) {
            throw new IllegalStateException(
                    "Blame named " + version.path() + " in " + commit.name() + ", which does not hold it");
        }
        // The first parent where the line has no version with the substring: where a move is
        // looked for, and where the version before comes from when none is found.
        RevCommit fallbackParent = null;
        Edit fallbackEdit = null;
        Next.Without without = null;
        for (RevCommit parent : commit.getParents()) {
            RevCommit before = walk.parseCommit(parent);
            String path = versions.pathIn(before, commit, version.path());
            RawText old = path == null ? null : versions.text(before, path);
            Edit edit;
            if (path == null || old == null) {
                edit = new Edit(0, 0, 0, now.size());
            } else {
                EditList edits = diff(RawTextComparator.DEFAULT, old, now);
                Edit hunk = editAt(edits, version.line());
                if (hunk == null) {
                    // Unchanged against this parent of a merge: the line came from that side as it is.
                    return new Next.Carried(new At(before, path, unchanged(edits, version.line())));
                }
                int carried = hunk.getLengthA() == 0
                        ? -1
                        : LineOrigin.carrying(old, hunk.getBeginA(), hunk.getEndA(), needle, version.text());
                if (carried >= 0) {
                    return new Next.Carried(new At(before, path, carried));
                }
                if (hunk.getLengthA() > 0 && without == null && fallbackParent == null) {
                    int closest = LineOrigin.closest(old, hunk.getBeginA(), hunk.getEndA(), version.text());
                    without = new Next.Without(new At(before, path, closest), FileVersions.lineOf(old, closest));
                }
                edit = hunk;
            }
            if (fallbackParent == null) {
                fallbackParent = before;
                fallbackEdit = edit;
            }
        }
        if (fallbackParent != null && fallbackEdit != null) {
            At moved = moves.find(
                    fallbackParent,
                    commit,
                    version.text(),
                    version.line(),
                    now,
                    fallbackEdit,
                    needle,
                    () -> checkDeadline(deadline, version.path()));
            if (moved != null) {
                return new Next.Carried(moved);
            }
        }
        return without != null ? without : new Next.None();
    }

    private static EditList diff(RawTextComparator comparator, RawText old, RawText now) {
        return new HistogramDiff().diff(comparator, old, now);
    }

    /** The hunk that holds line {@code line} on its new side; {@code null} when the line is unchanged. */
    private static @Nullable Edit editAt(EditList edits, int line) {
        for (Edit edit : edits) {
            if (edit.getBeginB() <= line && line < edit.getEndB()) {
                return edit;
            }
        }
        return null;
    }

    /** Where an unchanged line of the new side sits on the old one: shifted by every hunk above it. */
    private static int unchanged(EditList edits, int line) {
        int shift = 0;
        for (Edit edit : edits) {
            if (edit.getEndB() > line) {
                break;
            }
            shift += edit.getLengthA() - edit.getLengthB();
        }
        return line + shift;
    }

    /**
     * Whether git cannot look past the commit because this clone's history was cut there ({@code
     * shallow} in the common git directory names it — not the worktree's own, which a linked
     * worktree has apart): the line may be older than the clone.
     */
    private boolean shallowBoundary(RevCommit commit) {
        if (commit.getParentCount() > 0) {
            return false;
        }
        Path shallow = repository.getCommonDirectory().toPath().resolve("shallow");
        if (!Files.isRegularFile(shallow)) {
            return false;
        }
        try {
            return Files.readAllLines(shallow, StandardCharsets.US_ASCII).stream()
                    .anyMatch(commit.name()::equals);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + shallow, e);
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private void checkDeadline(long deadline, String path) {
        if (System.nanoTime() > deadline) {
            throw timedOut(path);
        }
    }

    private GitReadTimeoutException timedOut(String path) {
        return new GitReadTimeoutException(
                "Tracing the line did not finish within " + timeout.toSeconds() + "s: " + path);
    }

    private static GitLineOrigin.Step step(Version version) {
        PersonIdent author = version.commit().getAuthorIdent();
        OffsetDateTime date =
                author == null ? null : OffsetDateTime.ofInstant(author.getWhenAsInstant(), author.getZoneId());
        return new GitLineOrigin.Step(
                version.commit().name(),
                author == null ? null : author.getName(),
                date,
                version.commit().getShortMessage(),
                version.path(),
                version.line() + 1,
                LineOrigin.cap(version.text()));
    }

    /** The answer as the walk builds it: steps as they are taken, then {@code before} and the end. */
    private static final class Answer {
        private final String path;
        private final int line;
        private final String query;
        private final @Nullable String commit;
        // Written by the walk's thread and read by the caller's once it stopped waiting.
        private final List<GitLineOrigin.Step> steps = new CopyOnWriteArrayList<>();
        private volatile GitLineOrigin.@Nullable Step before;
        private volatile @Nullable Status settled;

        Answer(String path, int line, String query, @Nullable String commit) {
            this.path = path;
            this.line = line;
            this.query = query;
            this.commit = commit;
        }

        int walked() {
            return steps.size();
        }

        void step(GitLineOrigin.Step step) {
            steps.add(step);
        }

        void before(GitLineOrigin.Step step) {
            before = step;
        }

        /** The status the walk settled on, before it names the commit of the version before. */
        void settle(Status status) {
            settled = status;
        }

        @Nullable
        Status settled() {
            return settled;
        }

        GitLineOrigin status(Status status) {
            // A walk that ended before its first step has no versions, whatever it stopped on;
            // an uncommitted origin still names the committed line it replaced.
            List<GitLineOrigin.Step> walked =
                    status == Status.NOT_IN_LINE || status == Status.UNCOMMITTED ? List.of() : List.copyOf(steps);
            return new GitLineOrigin(path, line, query, commit, status, walked, before);
        }
    }
}
