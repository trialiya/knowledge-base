package io.github.trialiya.kb.service.file.git;

import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.jspecify.annotations.Nullable;

/**
 * Where a line of one version of a file sits in another: the diff between them, and the arithmetic
 * of reading a line number across it. Lines are 0-based here, as JGit counts them.
 */
final class LineShift {

    private LineShift() {}

    static EditList diff(RawTextComparator comparator, RawText old, RawText now) {
        return new HistogramDiff().diff(comparator, old, now);
    }

    /** The hunk that holds line {@code line} on its new side; {@code null} when the line is unchanged. */
    static @Nullable Edit editAt(EditList edits, int line) {
        for (Edit edit : edits) {
            if (edit.getBeginB() <= line && line < edit.getEndB()) {
                return edit;
            }
        }
        return null;
    }

    /** Where an unchanged line of the new side sits on the old one: shifted by every hunk above it. */
    static int unchanged(EditList edits, int line) {
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
     * A range of the new side as a range of the old one, both 1-based and inclusive: an unchanged
     * end moves with the hunks above it, an end inside a hunk widens to the lines that hunk
     * replaced. {@code null} when the range holds only added lines — the old side has nothing of it.
     */
    static GitBlame.@Nullable Lines toOld(EditList edits, GitBlame.Lines lines) {
        int from = lines.from() - 1;
        int to = lines.to() - 1;
        Edit first = editAt(edits, from);
        Edit last = editAt(edits, to);
        int oldFrom = first == null ? unchanged(edits, from) : first.getBeginA();
        int oldTo = last == null ? unchanged(edits, to) : last.getEndA() - 1;
        return oldFrom > oldTo ? null : new GitBlame.Lines(oldFrom + 1, oldTo + 1);
    }
}
