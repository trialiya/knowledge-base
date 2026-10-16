package io.github.trialiya.kb.service.file.git;

import org.eclipse.jgit.diff.DiffEntry;

/**
 * The status letter of a {@code GitDiffEntry} — the one place the letters are spelled. The DTO
 * keeps the letter itself: the panel, the model and scripts read it as a string, and it is git's own
 * {@code --name-status} vocabulary plus {@code U}.
 */
enum DiffStatus {
    ADDED("A"),
    MODIFIED("M"),
    DELETED("D"),
    RENAMED("R"),
    COPIED("C"),
    /**
     * An untracked file the project's {@code allow-globs} admit. git's own letters say what the
     * index holds; this one says the index holds nothing.
     */
    UNTRACKED("U");

    private final String code;

    DiffStatus(String code) {
        this.code = code;
    }

    /** The letter as it goes out in {@code GitDiffEntry.status}. */
    String code() {
        return code;
    }

    /** Whether {@code status}, as a {@code GitDiffEntry} carries it, is this one. */
    boolean is(String status) {
        return code.equals(status);
    }

    /** The status of a change as JGit typed it. */
    static DiffStatus of(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> ADDED;
            case DELETE -> DELETED;
            case RENAME -> RENAMED;
            case COPY -> COPIED;
            case MODIFY -> MODIFIED;
        };
    }

    /** Renames and copies — the two that name the path the file came from. */
    boolean hasSource() {
        return this == RENAMED || this == COPIED;
    }
}
