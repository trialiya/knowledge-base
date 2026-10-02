package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin.Status;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
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
 *       goes on from it; when none has it, the substring entered with this commit, and the old
 *       line most like it is the answer's {@code before};
 *   <li>the line sits in a hunk that only inserted lines, or the file is new — the line was added
 *       here, unless the same commit deleted the same block from another file (a move, {@link
 *       #moved}); then the walk goes on in that file.
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
     * Files a commit may change and still be searched for a block moved into the line's file: the
     * search reads the old side of every one of them, and a commit past this is a vendored drop or
     * a mass rename, not an extraction.
     */
    static final int MAX_MOVE_FILES = 300;

    /** The largest file version read, as for any read of history ({@link CommitFiles}). */
    private static final long MAX_BLOB_BYTES = 32L * 1024 * 1024;

    private final Repository repository;
    private final VisibleFiles visible;
    private final Duration timeout;

    LineOriginTracer(Repository repository, VisibleFiles visible) {
        this(repository, visible, TIMEOUT);
    }

    /** With the deadline spelled out — a test's way of seeing one expire. */
    LineOriginTracer(Repository repository, VisibleFiles visible, Duration timeout) {
        this.repository = repository;
        this.visible = visible;
        this.timeout = timeout;
    }

    /** A line of a file version: 0-based, as JGit counts. */
    private record At(RevCommit commit, String path, int line) {}

    /** A version of the line: the commit that last changed it, where it sits there, and its text. */
    private record Version(RevCommit commit, String path, int line, String text) {}

    /** Where the walk goes from a version: on, to the version before; or it stops. */
    private sealed interface Next {
        /** The version before still has the substring: the walk goes on from it. */
        record Carried(At at) implements Next {}
        /** The version before has no substring: it entered here, and this is what it was. */
        record Without(At at) implements Next {}
        /** No version before: the line was added here, or history ends here. */
        record None() implements Next {}
    }

    /**
     * @param rev resolved commit to start at; {@code null} — the working tree
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
        // The walk runs on a thread of its own and is waited for no longer than the deadline: a
        // single step of JGit's blame can walk a long history before it returns, and checks of the
        // deadline between steps would not cut it short. The thread is not interrupted — JGit
        // reads packs through channels that an interrupt closes for the whole repository — but
        // left to stop at its own next check, and its late answer is dropped.
        CompletableFuture<GitLineOrigin> result = new CompletableFuture<>();
        Thread.ofVirtual().name("line-origin").start(() -> {
            try {
                result.complete(trace(normalized, rev, line, query, deadline, progress));
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            Answer answer = progress.get();
            if (answer == null || answer.walked() == 0) {
                GitReadTimeoutException timeout = timedOut(normalized);
                timeout.initCause(e);
                throw timeout;
            }
            return answer.status(Status.LIMIT);
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
     * The walk itself, on the walk's own thread.
     *
     * @param progress where the answer being built is published, so a caller that stopped waiting
     *     can still hand out the versions walked so far
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
            if (start.at() == null) {
                At before = start.before();
                return answer.before(before == null ? null : versionBefore(walk, before, deadline))
                        .status(Status.UNCOMMITTED);
            }
            return walkFrom(walk, start.at(), needle, deadline, answer);
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
     *     replaced; {@code null} when the edit added the line
     */
    private record Start(
            @Nullable String commit,
            @Nullable At at,
            @Nullable String text,
            @Nullable At before) {

        Start(@Nullable String commit, @Nullable At at, @Nullable String text) {
            this(commit, at, text, null);
        }
    }

    private Start snapshot(RevWalk walk, String rev, String normalized, int line) throws IOException {
        CommitFiles.Blob blob = CommitFiles.read(repository, rev, normalized);
        requireText(normalized, blob.bytes());
        RawText file = new RawText(blob.bytes());
        int index = line - 1;
        if (index >= file.size()) {
            return new Start(blob.commit(), null, null);
        }
        RevCommit commit = walk.parseCommit(ObjectId.fromString(blob.commit()));
        return new Start(blob.commit(), new At(commit, normalized, index), lineOf(file, index));
    }

    /**
     * The working-tree line, carried into HEAD the way {@link #previous} carries a line into a
     * parent — the working tree is one more version on top of history: an unchanged line goes
     * through as it is; an edited one through the old line of its hunk that has the substring; one
     * the edit added, or whose hunk had no substring, is where the substring entered — uncommitted.
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
        if (RepoFiles.sizeOf(normalized, resolved.absolute()) > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("File too large to trace: " + normalized);
        }
        byte[] bytes = RepoFiles.readAll(normalized, resolved.absolute());
        requireText(normalized, bytes);
        RawText work = new RawText(bytes);
        int index = line - 1;
        if (index >= work.size()) {
            return new Start(null, null, null);
        }
        String text = lineOf(work, index);
        RevCommit headCommit = walk.parseCommit(head);
        RawText committed = text(headCommit, normalized);
        if (committed == null) {
            // Tracked but not in HEAD: staged and never committed, so is every line of it.
            return new Start(null, null, text);
        }
        // Trailing whitespace aside: a working copy checked out with CRLF must not read as every
        // line edited.
        EditList edits = diff(RawTextComparator.WS_IGNORE_TRAILING, committed, work);
        Edit edit = editAt(edits, index);
        if (edit == null) {
            return new Start(null, new At(headCommit, normalized, unchanged(edits, index)), text);
        }
        if (edit.getLengthA() == 0) {
            return new Start(null, null, text);
        }
        int carried = LineOrigin.carrying(committed, edit.getBeginA(), edit.getEndA(), needle, text);
        if (carried >= 0) {
            return new Start(null, new At(headCommit, normalized, carried), text);
        }
        int closest = LineOrigin.closest(committed, edit.getBeginA(), edit.getEndA(), text);
        return new Start(null, null, text, new At(headCommit, normalized, closest));
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
            } catch (GitReadTimeoutException | Unreadable e) {
                // Out of time, or a version of the file history cannot be read as text (past the
                // size cap, or binary then): the oldest version reached is as far as it goes.
                return answer.status(Status.LIMIT);
            }
            switch (next) {
                case Next.Carried carried -> at = carried.at();
                case Next.Without without -> {
                    return answer.before(versionBefore(walk, without.at(), deadline))
                            .status(Status.FOUND);
                }
                case Next.None() -> {
                    return answer.status(shallowBoundary(version.commit()) ? Status.BOUNDARY : Status.FOUND);
                }
            }
        }
    }

    /**
     * The version without the substring, as a step of its own; {@code null} when the deadline ran
     * out first — the origin is known by then, and only what came before it is left out.
     */
    private GitLineOrigin.@Nullable Step versionBefore(RevWalk walk, At at, long deadline) throws IOException {
        try {
            return step(blame(walk, at, deadline));
        } catch (GitReadTimeoutException e) {
            return null;
        }
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
                    return new Version(commit, blame.getSourcePath(), line, lineOf(blame.getSourceContents(), line));
                }
            }
        }
        throw new IllegalStateException("Blame named no commit for " + at.path() + ":" + (at.line() + 1));
    }

    /**
     * The version of the line before {@code version}'s commit: through each parent's diff of the
     * file in turn (a merge has more than one), then through a block moved in from another file.
     */
    private Next previous(RevWalk walk, Version version, Pattern needle, long deadline) throws IOException {
        RevCommit commit = version.commit();
        RawText now = text(commit, version.path());
        if (now == null) {
            throw new IllegalStateException(
                    "Blame named " + version.path() + " in " + commit.name() + ", which does not hold it");
        }
        Next.Without without = null;
        Edit inserted = null;
        for (RevCommit parent : commit.getParents()) {
            RevCommit before = walk.parseCommit(parent);
            String path = pathIn(before, commit, version.path());
            RawText old = path == null ? null : text(before, path);
            if (path == null || old == null) {
                inserted = new Edit(0, 0, 0, now.size());
                continue;
            }
            EditList edits = diff(RawTextComparator.DEFAULT, old, now);
            Edit edit = editAt(edits, version.line());
            if (edit == null) {
                // Unchanged against this parent of a merge: the line came from that side as it is.
                return new Next.Carried(new At(before, path, unchanged(edits, version.line())));
            }
            if (edit.getLengthA() == 0) {
                inserted = edit;
                continue;
            }
            int carried = LineOrigin.carrying(old, edit.getBeginA(), edit.getEndA(), needle, version.text());
            if (carried >= 0) {
                return new Next.Carried(new At(before, path, carried));
            }
            if (without == null) {
                int closest = LineOrigin.closest(old, edit.getBeginA(), edit.getEndA(), version.text());
                without = new Next.Without(new At(before, path, closest));
            }
        }
        if (without != null) {
            return without;
        }
        if (inserted != null && commit.getParentCount() > 0) {
            At moved = moved(walk.parseCommit(commit.getParent(0)), commit, version, now, inserted, needle, deadline);
            if (moved != null) {
                return new Next.Carried(moved);
            }
        }
        return new Next.None();
    }

    /**
     * Where a line added by {@code commit} was moved in from: the same commit deleted the same block
     * from another file — line for line, indentation aside — and the run of matching lines that
     * holds this one carries at least {@link LineOrigin#MOVE_MIN_ALNUM} letters and digits. The
     * longest such run wins. A run with no substring in the line itself does not count: then the
     * substring entered with the move, not before it.
     *
     * @param inserted the hunk of the line's file that added the line ({@code 0..size} for a new
     *     file): the run is matched inside it, on the new side
     */
    private @Nullable At moved(
            RevCommit parent,
            RevCommit commit,
            Version version,
            RawText now,
            Edit inserted,
            Pattern needle,
            long deadline)
            throws IOException {
        try (DiffFormatter diffs = new DiffFormatter(OutputStream.nullOutputStream())) {
            diffs.setRepository(repository);
            diffs.setDiffComparator(RawTextComparator.DEFAULT);
            diffs.setDetectRenames(true);
            List<DiffEntry> entries = diffs.scan(parent.getTree(), commit.getTree());
            if (entries.size() > MAX_MOVE_FILES) {
                return null;
            }
            At best = null;
            int bestWeight = LineOrigin.MOVE_MIN_ALNUM - 1;
            for (DiffEntry entry : entries) {
                // Every file read here may be large: the walk's deadline holds through the search.
                checkDeadline(deadline, version.path());
                if (entry.getChangeType() == DiffEntry.ChangeType.ADD
                        || version.path().equals(entry.getNewPath())) {
                    continue;
                }
                RawText old;
                try {
                    old = text(parent, entry.getOldPath());
                } catch (Unreadable e) {
                    continue;
                }
                if (old == null) {
                    continue;
                }
                for (Edit edit : diffs.toFileHeader(entry).toEditList()) {
                    for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                        if (!LineOrigin.sameLine(old.getString(i), version.text())) {
                            continue;
                        }
                        int weight = runWeight(old, edit, i, now, inserted, version.line());
                        if (weight > bestWeight
                                && needle.matcher(old.getString(i)).find()) {
                            bestWeight = weight;
                            best = new At(parent, entry.getOldPath(), i);
                        }
                    }
                }
            }
            return best;
        }
    }

    /**
     * Letters and digits of the run of lines that match around line {@code a} of the deleted hunk
     * and line {@code b} of the inserted one, both kept inside their hunks.
     */
    private static int runWeight(RawText old, Edit deleted, int a, RawText now, Edit inserted, int b) {
        int up = 0;
        while (a - up - 1 >= deleted.getBeginA()
                && b - up - 1 >= inserted.getBeginB()
                && LineOrigin.sameLine(old.getString(a - up - 1), now.getString(b - up - 1))) {
            up++;
        }
        int down = 0;
        while (a + down + 1 < deleted.getEndA()
                && b + down + 1 < inserted.getEndB()
                && LineOrigin.sameLine(old.getString(a + down + 1), now.getString(b + down + 1))) {
            down++;
        }
        int weight = 0;
        for (int i = a - up; i <= a + down; i++) {
            weight += LineOrigin.alnum(old.getString(i));
        }
        return weight;
    }

    /** The file's path in {@code parent}: the same, or where {@code commit} renamed it from. */
    private @Nullable String pathIn(RevCommit parent, RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, parent.getTree())) {
            if (tree != null) {
                return path;
            }
        }
        try (DiffFormatter diffs = new DiffFormatter(OutputStream.nullOutputStream())) {
            diffs.setRepository(repository);
            diffs.setDetectRenames(true);
            for (DiffEntry entry : diffs.scan(parent.getTree(), commit.getTree())) {
                if (entry.getChangeType() == DiffEntry.ChangeType.RENAME && path.equals(entry.getNewPath())) {
                    return entry.getOldPath();
                }
            }
        }
        return null;
    }

    /**
     * The file as of the commit; {@code null} when the commit holds no file there (or a directory,
     * a link: no lines of it).
     *
     * @throws Unreadable when there is a file but not one to read lines from: past {@link
     *     #MAX_BLOB_BYTES}, or binary
     */
    private @Nullable RawText text(RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (tree == null || (tree.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
                return null;
            }
            ObjectLoader blob = repository.open(tree.getObjectId(0));
            if (blob.getSize() > MAX_BLOB_BYTES) {
                throw new Unreadable(path + " in " + commit.name() + " is too large");
            }
            byte[] bytes = blob.getCachedBytes((int) MAX_BLOB_BYTES);
            if (RawText.isBinary(bytes)) {
                throw new Unreadable(path + " in " + commit.name() + " is binary");
            }
            return new RawText(bytes);
        }
    }

    /**
     * A version of a file the walk needs and cannot read as lines. Not a failure of the request: the
     * walk stops at the version it reached ({@link Status#LIMIT}), and the search for a moved block
     * passes such a file over.
     */
    private static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message) {
            super(message, null, false, false);
        }
    }

    /** Line {@code index} of the text without its line ending — a CRLF file's {@code \r} included. */
    private static String lineOf(RawText text, int index) {
        String line = text.getString(index);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
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

    private static void requireText(String normalized, byte[] bytes) {
        if (RepoFiles.isBinary(bytes)) {
            throw new IllegalArgumentException("Binary file has no line history: " + normalized);
        }
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

        Answer before(GitLineOrigin.@Nullable Step step) {
            before = step;
            return this;
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
