package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;

/**
 * Where a line a commit put in was moved from — the tracer's own {@code git blame -C}: the same
 * commit deleted the same block elsewhere, in another file or another place of this one, line for
 * line (indentation aside), and the run of matching lines holding this one carries at least {@link
 * LineOrigin#MOVE_MIN_ALNUM} letters and digits. The heaviest run wins.
 */
final class MovedBlocks {

    /**
     * Files a commit may change and still be searched: the search reads the old side of each one
     * that holds the line, and a commit past this is a vendored drop or a mass rename, not an
     * extraction.
     */
    static final int MAX_FILES = 300;

    private final Repository repository;
    private final FileVersions versions;

    MovedBlocks(Repository repository, FileVersions versions) {
        this.repository = repository;
        this.versions = versions;
    }

    /**
     * @param line the line as {@code commit} put it in: its text, and its place in {@code now}
     * @param now the line's file as of {@code commit}
     * @param put the hunk of that file, against {@code parent}, that put the line in — the run is
     *     matched inside it on the new side ({@code 0..size} for a file the commit created)
     * @param needle a run whose line has no substring does not count: the substring entered with
     *     the move then, not before it
     * @param inTime called before each file is read, so the walk's deadline holds through the
     *     search
     * @return the line in {@code parent} it came from; {@code null} when no block counts as moved
     */
    FileVersions.@Nullable At find(
            RevCommit parent,
            RevCommit commit,
            String line,
            int at,
            RawText now,
            Edit put,
            Pattern needle,
            Runnable inTime)
            throws IOException {
        try (DiffFormatter diffs = new DiffFormatter(OutputStream.nullOutputStream())) {
            diffs.setRepository(repository);
            diffs.setDiffComparator(RawTextComparator.DEFAULT);
            diffs.setDetectRenames(true);
            List<DiffEntry> entries = diffs.scan(parent.getTree(), commit.getTree());
            if (entries.size() > MAX_FILES) {
                return null;
            }
            FileVersions.At best = null;
            int bestWeight = LineOrigin.MOVE_MIN_ALNUM - 1;
            for (DiffEntry entry : entries) {
                if (entry.getChangeType() == DiffEntry.ChangeType.ADD) {
                    continue;
                }
                inTime.run();
                RawText old = oldSide(parent, entry, line);
                if (old == null) {
                    continue;
                }
                for (Edit edit : diffs.toFileHeader(entry).toEditList()) {
                    for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                        if (!LineOrigin.sameLine(old.getString(i), line)) {
                            continue;
                        }
                        int weight = runWeight(old, edit, i, now, put, at);
                        if (weight > bestWeight
                                && needle.matcher(old.getString(i)).find()) {
                            bestWeight = weight;
                            best = new FileVersions.At(parent, entry.getOldPath(), i);
                        }
                    }
                }
            }
            return best;
        }
    }

    /**
     * The old side of a changed file, when it can hold the line at all: one with no line equal to
     * it is passed over before its diff is worked out, and so is one that cannot be read as lines.
     */
    private @Nullable RawText oldSide(RevCommit parent, DiffEntry entry, String line) throws IOException {
        RawText old;
        try {
            old = versions.text(parent, entry.getOldPath());
        } catch (FileVersions.Unreadable e) {
            return null;
        }
        if (old == null) {
            return null;
        }
        for (int i = 0; i < old.size(); i++) {
            if (LineOrigin.sameLine(old.getString(i), line)) {
                return old;
            }
        }
        return null;
    }

    /**
     * Letters and digits of the run of lines that match around line {@code a} of the deleted hunk
     * and line {@code b} of the inserted one, both kept inside their hunks.
     */
    private static int runWeight(RawText old, Edit deleted, int a, RawText now, Edit put, int b) {
        int up = 0;
        while (a - up - 1 >= deleted.getBeginA()
                && b - up - 1 >= put.getBeginB()
                && LineOrigin.sameLine(old.getString(a - up - 1), now.getString(b - up - 1))) {
            up++;
        }
        int down = 0;
        while (a + down + 1 < deleted.getEndA()
                && b + down + 1 < put.getEndB()
                && LineOrigin.sameLine(old.getString(a + down + 1), now.getString(b + down + 1))) {
            down++;
        }
        int weight = 0;
        for (int i = a - up; i <= a + down; i++) {
            weight += LineOrigin.alnum(old.getString(i));
        }
        return weight;
    }
}
