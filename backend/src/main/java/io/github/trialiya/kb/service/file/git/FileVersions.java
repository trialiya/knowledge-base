package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.io.OutputStream;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * Versions of a file in history, as lines: what tracing a line's origin ({@link LineOriginTracer},
 * {@link MovedBlocks}) reads at every step.
 */
final class FileVersions {

    /** A line of a file version: 0-based, as JGit counts. */
    record At(RevCommit commit, String path, int line) {}

    private final Repository repository;

    FileVersions(Repository repository) {
        this.repository = repository;
    }

    /**
     * The file as of the commit; {@code null} when the commit holds no file there (or a directory,
     * a link: no lines of it).
     *
     * @throws Unreadable when there is a file but not one to read lines from: past {@link
     *     CommitFiles#MAX_BLOB_SIZE}, as any read of history, or binary
     */
    @Nullable
    RawText text(RevCommit commit, String path) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (tree == null || (tree.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
                return null;
            }
            ObjectLoader blob = repository.open(tree.getObjectId(0));
            if (blob.getSize() > CommitFiles.MAX_BLOB_SIZE) {
                throw new Unreadable(path + " in " + commit.name() + " is too large");
            }
            byte[] bytes = blob.getCachedBytes((int) CommitFiles.MAX_BLOB_SIZE);
            // The project's own test, as the read that accepted the file used: JGit's would also call
            // a text file with a lone carriage return binary.
            if (RepoFiles.isBinary(bytes)) {
                throw new Unreadable(path + " in " + commit.name() + " is binary");
            }
            return new RawText(bytes);
        }
    }

    /** The file's path in {@code parent}: the same, or where {@code commit} renamed it from. */
    @Nullable
    String pathIn(RevCommit parent, RevCommit commit, String path) throws IOException {
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

    /** Line {@code index} of the text without its line ending — a CRLF file's {@code \r} included. */
    static String lineOf(RawText text, int index) {
        String line = text.getString(index);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * A version of a file the walk needs and cannot read as lines. Not a failure of the request: the
     * walk stops at the version it reached, and the search for a moved block passes such a file
     * over.
     */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message) {
            super(message, null, false, false);
        }
    }
}
