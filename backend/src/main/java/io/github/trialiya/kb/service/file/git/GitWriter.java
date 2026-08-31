package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.git.dto.TextEdit;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.service.file.git.VisibleFiles.Resolved;
import io.github.trialiya.kb.utils.ExactEdit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jspecify.annotations.NonNull;

/**
 * Working-tree writes for one project: {@code createFile} / {@code editFile} and the whole-content
 * replacements {@code runScript} applies, plus the refusals each of them checks before anything
 * reaches disk.
 *
 * <p>Reached through {@link GitService}, which is what callers hold; the split is so that the rules
 * a write has to obey — where it may write, what it may overwrite, what it must stage — sit apart
 * from the far larger read surface instead of being interleaved with it.
 *
 * <p>Every write ends staged rather than committed: the user reviews the change ({@code
 * getUncommittedChanges}) and commits it themselves.
 */
@Slf4j
final class GitWriter {

    private final Project project;
    private final RepoPaths paths;
    private final VisibleFiles visible;
    private final Git git;

    GitWriter(Project project, RepoPaths paths, VisibleFiles visible, Git git) {
        this.project = project;
        this.paths = paths;
        this.visible = visible;
        this.git = git;
    }

    // ── Creating ────────────────────────────────────────────────────────────

    /**
     * Creates a new file in the working tree, visible to every read tool from the moment it
     * returns.
     *
     * <p>That means staging it ({@code git add}), since the read tools serve tracked files.
     *
     * <p>Refused when: the path falls inside this project's {@code allow-globs} (that area holds
     * what something else produces — see {@link #requireCreatable}), the path already exists on
     * disk, the path is matched by {@code .gitignore} (staging would silently skip it, leaving an
     * unreadable orphan — the file is removed again and the call fails), the name is an OS/IDE junk
     * artefact, or the content exceeds {@value RepoFiles#MAX_FILE_SIZE} bytes.
     */
    GitEditResult createFile(@NonNull String filePath, @NonNull String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String normalized = requireCreatable(filePath, bytes);
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return create(normalized, bytes, lines);
    }

    /**
     * As {@link #createFile}, from raw bytes — the only way to create a file whose content is not
     * text (a fixture image, a keystore, a compiled artefact a test compares against).
     *
     * <p>Line counters come back as zero rather than as a count of accidental {@code 0x0A} bytes: a
     * binary file has no lines, and a number that looks like one would be read as if it did.
     */
    GitEditResult createBinaryFile(@NonNull String filePath, byte @NonNull [] content) {
        return create(requireCreatable(filePath, content), content, 0);
    }

    /** Shared tail of the two create paths: write, stage, report. */
    private GitEditResult create(String normalized, byte[] content, int lines) {
        // Only presence on disk blocks creation. A tracked-but-deleted file (removed from the
        // working tree, still in the index) is deliberately allowed — editFile can't read it, so
        // createFile is the only way to restore it; the staging below refreshes the index entry.
        Path absolute = paths.resolve(normalized);
        if (Files.exists(absolute)) {
            throw new IllegalArgumentException(
                    "File already exists: " + normalized + ". Use editFile to modify it.");
        }

        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(absolute, content);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create file: " + normalized, e);
        }

        // Stage the new file so it becomes tracked. JGit's AddCommand honours .gitignore: an
        // ignored path is silently NOT added — detect that, roll the write back and fail loudly
        // instead of leaving an untracked file no read tool can see.
        try {
            stage(normalized);
        } catch (RuntimeException e) {
            deleteQuietly(absolute);
            throw e;
        }
        if (!visible.isTracked(normalized)) {
            deleteQuietly(absolute);
            throw new IllegalArgumentException(
                    "Path is ignored by .gitignore and cannot be created: " + normalized);
        }

        log.info("createFile: '{}' created and staged ({} bytes)", normalized, content.length);
        return new GitEditResult("create", normalized, lines, 0, lines, null);
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /**
     * Replaces an exact occurrence of {@code oldString} with {@code newString} in a text file the
     * read tools serve and stages the result (nothing is committed). An untracked file admitted by
     * the project's {@code allow-globs} is edited in place and left untracked, and only where the
     * project allows that at all (see {@link #resolveEditable}).
     *
     * <p>The match is exact and unique by default: zero occurrences or more than one (without
     * {@code replaceAll}) fail with a model-readable error, so the model must quote real, current
     * file content — this doubles as an optimistic concurrency check. Content is matched against
     * the LF-normalised text (the same view {@code getFileContent} returns); original CRLF line
     * endings are preserved on write. Binary files and files over {@value RepoFiles#MAX_FILE_SIZE}
     * bytes are refused.
     *
     * @return counters plus a unified diff of exactly this edit (truncated to {@value
     *     Diffs#MAX_DIFF_LINES} lines)
     */
    GitEditResult editFile(
            @NonNull String filePath,
            @NonNull String oldString,
            @NonNull String newString,
            boolean replaceAll) {
        // Before the read: a fragment that could not edit anything is an argument error, and
        // saying so must not depend on whether the file opens.
        ExactEdit.requireUsableFragment(oldString, newString);

        Editable file = readEditable(filePath);
        ExactEdit.Result edit =
                ExactEdit.replace(
                        file.text(),
                        oldString.replace("\r\n", "\n"),
                        newString.replace("\r\n", "\n"),
                        replaceAll,
                        file.path(),
                        "getFileContent");

        log.info("editFile: '{}' — {} occurrence(s) replaced", file.path(), edit.occurrences());
        return writeUpdatedText(file, edit.text());
    }

    /**
     * Replaces the whole text of a tracked file, with the same write/stage semantics and the same
     * refusals (binary, too large) as {@link #editFile}.
     *
     * <p>Exists for {@code runScript}: a script may edit one file several times, and each of those
     * edits was already validated against the pending text as it accumulated (see {@code
     * ScriptSession}). Replaying them one by one here would re-do that work and multiply the
     * writes; writing the final text once keeps a script's changes to one atomic write and one diff
     * per file. Not exposed as a tool — the exact-match contract of {@link #editFile} is what
     * forces a model to quote real content, and nothing should be able to skip it.
     */
    GitEditResult replaceTrackedFile(@NonNull String filePath, @NonNull String newContent) {
        return writeUpdatedText(readEditable(filePath), newContent.replace("\r\n", "\n"));
    }

    /**
     * Replaces the whole content of a tracked file with raw bytes — the binary counterpart of
     * {@link #replaceTrackedFile}, and like it not exposed as a tool of its own.
     *
     * <p>Whole-content only, because there is no meaningful partial edit here: the exact-match
     * contract of {@link #editFile} is defined on text, and a byte offset carries none of the
     * evidence that the caller is looking at what it thinks it is. What the user reviews is
     * therefore not a diff but git's own answer for a binary change — the two sizes and the fact
     * that they differ.
     */
    GitEditResult replaceTrackedBytes(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = requireWritable(filePath, content);
        Resolved resolved = resolveEditable(normalized);
        long before = RepoFiles.sizeOf(normalized, resolved.absolute());

        writeAtomically(normalized, content);
        stageIfTracked(normalized, resolved.tracked());

        log.info("wrote '{}' ({} → {} bytes)", normalized, before, content.length);
        String diff =
                "Binary files a/"
                        + normalized
                        + " and b/"
                        + normalized
                        + " differ ("
                        + before
                        + " → "
                        + content.length
                        + " bytes)";
        return new GitEditResult("edit", normalized, 0, 0, 0, diff);
    }

    /**
     * A tracked text file, read and validated for editing: its LF-normalised text, whether the
     * bytes on disk used CRLF — which the write has to put back — and whether git knows the path.
     *
     * @param tracked straight from the gate {@link #readEditable} ran, so the write at the end of
     *     the edit decides on staging without a second look at the index. Only {@link
     *     #readEditable} builds one of these, and it always runs that gate.
     */
    private record Editable(String path, String text, boolean crlf, boolean tracked) {}

    /**
     * Reads a tracked file the edit paths may write to: not binary, not oversized, decoded as UTF-8
     * and normalised to LF — the same view {@code getFileContent} returns, which is what the
     * exact-match contract of {@link #editFile} is defined against.
     */
    private Editable readEditable(String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        Resolved resolved = resolveEditable(normalized);
        byte[] bytes = RepoFiles.readAll(normalized, resolved.absolute());
        if (RepoFiles.isBinary(bytes)) {
            throw new IllegalArgumentException("Cannot edit a binary file: " + normalized);
        }
        if (bytes.length > RepoFiles.MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "File too large to edit (max "
                            + RepoFiles.MAX_FILE_SIZE / 1024
                            + " KB): "
                            + normalized);
        }
        String original = new String(bytes, StandardCharsets.UTF_8);
        boolean crlf = original.contains("\r\n");
        return new Editable(
                normalized,
                crlf ? original.replace("\r\n", "\n") : original,
                crlf,
                resolved.tracked());
    }

    /** Shared tail of the two edit paths: diff, atomic write, stage, report. */
    private GitEditResult writeUpdatedText(Editable file, String updated) {
        String path = file.path();
        Diffs.Stats stats = Diffs.between(file.text(), updated);
        writeAtomically(path, file.crlf() ? updated.replace("\n", "\r\n") : updated);
        stageIfTracked(path, file.tracked());

        int lines = updated.isEmpty() ? 0 : updated.split("\n", -1).length;
        log.info("wrote '{}' (+{}/-{})", path, stats.additions(), stats.deletions());
        return new GitEditResult(
                "edit", path, stats.additions(), stats.deletions(), lines, stats.diff());
    }

    // ── Undoing ─────────────────────────────────────────────────────────────

    /**
     * Applies {@code edits} to the file's current text and returns the result <b>without writing
     * it</b>, so a caller changing several files can find out that one of them no longer matches
     * before any of them is touched (see {@code ChatFileRevert}).
     *
     * <p>The text is read and matched exactly as {@link #editFile} does it — same LF-normalised
     * view, same exact-match contract — which is what makes an undo safe without storing anything:
     * a file edited by someone else since simply stops matching, and the caller is refused instead
     * of overwriting that work. The write itself is {@link #replaceTrackedFile}, on the very text
     * this returned.
     *
     * @param edits applied in the given order, each to the result of the previous one
     */
    String previewEdited(@NonNull String filePath, @NonNull List<TextEdit> edits) {
        Editable file = readEditable(filePath);
        String text = file.text();
        for (TextEdit edit : edits) {
            text =
                    ExactEdit.replace(
                                    text,
                                    edit.oldString().replace("\r\n", "\n"),
                                    edit.newString().replace("\r\n", "\n"),
                                    edit.replaceAll(),
                                    file.path(),
                                    "getFileContent")
                            .text();
        }
        return text;
    }

    /**
     * Everything {@link #deleteFile} refuses before it removes anything: an unwritable path, a file
     * no read tool serves (or an untracked one on a project that does not allow untracked edits), a
     * path HEAD already has, and content that is no longer the content {@code expectedContent}
     * describes.
     *
     * <p>Those last two are what keep an undo from undoing more than it was asked to. A file the
     * assistant created and the user then committed is part of the repository's history now, and
     * deleting it is a change of its own — git's to make, on the user's word. A file the user has
     * since edited holds their work, and a deletion would take it with no way back; the comparison
     * is the same integrity check the exact match gives an edit ({@link #previewEdited}), which is
     * why an undo can be safe without storing anything.
     *
     * <p>Split out for the same reason as {@link #requireCreatable}: a caller deleting several
     * files finds out here that one of them cannot go, with nothing removed yet.
     *
     * @param expectedContent the text the file was created with, as its creator passed it
     */
    void requireDeletable(@NonNull String filePath, @NonNull String expectedContent) {
        String normalized = validateWritablePath(filePath);
        resolveEditable(normalized);
        if (committed(normalized)) {
            throw new IllegalArgumentException(
                    "Cannot delete " + normalized + ": it is committed. Use git to remove it.");
        }
        if (!readEditable(normalized).text().equals(expectedContent.replace("\r\n", "\n"))) {
            throw new IllegalArgumentException(
                    "Cannot delete "
                            + normalized
                            + ": it has changed since it was created, and deleting it would take"
                            + " those changes with it.");
        }
    }

    /**
     * Removes a file from the working tree and from the index — the undo of {@link #createFile},
     * and the only deletion this service does at all.
     *
     * <p>Re-checks {@link #requireDeletable} rather than trusting a caller that already asked:
     * between the two the tree can have moved, and the checks are a stat and an index lookup.
     */
    void deleteFile(@NonNull String filePath, @NonNull String expectedContent) {
        String normalized = validateWritablePath(filePath);
        Resolved resolved = resolveEditable(normalized);
        requireDeletable(normalized, expectedContent);
        try {
            Files.delete(resolved.absolute());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete file: " + normalized, e);
        }
        if (resolved.tracked()) {
            unstage(normalized);
        }
        log.info("deleteFile: '{}' removed from the working tree", normalized);
    }

    /** Whether the last commit has this path — the one state a deletion here must not touch. */
    private boolean committed(String normalized) {
        try {
            return git.getRepository().resolve(Constants.HEAD + ":" + normalized) != null;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read HEAD for " + normalized, e);
        }
    }

    // ── Refusals a caller can ask for on its own ────────────────────────────

    /**
     * Everything {@link #createFile} refuses before it touches the disk: an unsafe or unwritable
     * path ({@code .git/}, a junk name, an escape from the tree) and content too large to serve
     * back afterwards.
     *
     * <p>Split out for {@code kb.create}, which stages its writes and applies them only once the
     * script has finished. Those refusals do not depend on the state of the tree, so leaving them
     * to the apply step would turn a script's own mistake — {@code kb.create('.git/hooks/x')} on
     * the third of five files — into two files written and a run that failed anyway, which is
     * exactly the outcome buffering exists to prevent. Checked while the script is still running,
     * it is an ordinary {@code RUNTIME} error the model can correct, and nothing reaches disk.
     *
     * @return the normalized path, as {@link #createFile} will spell it
     */
    String requireCreatable(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = requireWritable(filePath, content);
        // Nothing is created that would stay untracked. The allow-globs area is served for reading
        // and editing what is already there — the files in it are produced by something else (a
        // build, a person's notes) — and a new one would either be staged out of that area or live
        // outside git for good. The refusal depends only on the path, so it belongs here, where a
        // script's third kb.create fails before the first two have reached disk.
        if (!visible.isTracked(normalized) && visible.matchesAllowGlobs(normalized)) {
            throw new IllegalArgumentException(
                    "Cannot create files under the project's allow-globs: "
                            + normalized
                            + ". Files there can be read and edited, not created.");
        }
        return normalized;
    }

    /**
     * Everything {@link #replaceTrackedBytes} refuses before it touches the disk: a path that is
     * not writable ({@code .git/}, a junk name, an escape from the tree), a path no read tool would
     * serve back afterwards (untracked and not admitted), an admitted untracked one on a project
     * that does not allow those edits, and content too large.
     *
     * <p>Split out for the same reason as {@link #requireCreatable}.
     *
     * @return the normalized path, as {@link #replaceTrackedBytes} will spell it
     */
    String requireReplaceable(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = requireWritable(filePath, content);
        resolveEditable(normalized);
        return normalized;
    }

    /**
     * That an existing file may be edited at all: it is visible (the read gate), and — when git
     * does not track it — this project allows untracked edits.
     *
     * <p>Split out for {@code kb.edit}, whose writes only reach disk once the whole script has
     * finished: without this the run would edit its pending copy of a read-only untracked file
     * happily and fail at apply time, with the run's earlier files already written. Same argument
     * as {@link #requireCreatable}.
     *
     * @return what the gate established, so the read that follows the check does not have to ask
     *     the index the same question a second time
     */
    Resolved requireEditable(@NonNull String filePath) {
        return resolveEditable(RepoPaths.normalize(filePath));
    }

    /**
     * The gate every write to an <em>existing</em> file passes: the read gate ({@link
     * VisibleFiles#require}), then — when git does not track the path — this project's permission
     * to edit an untracked file at all.
     *
     * <p>The path is visible by the time the second half runs — the {@code allow-globs} admitted it
     * — so its refusal says what it really is: readable, not writable. That is the whole difference
     * between it and the read gate's deliberately uninformative "File not found", which must not
     * reveal whether an unadmitted path exists.
     *
     * @param normalized already through {@link RepoPaths#normalize}
     */
    private Resolved resolveEditable(String normalized) {
        Resolved resolved = visible.require(normalized);
        if (resolved.tracked() || project.untrackedEditEnabled()) {
            return resolved;
        }
        throw new IllegalArgumentException(
                "File is untracked and this project only serves untracked files for reading: "
                        + normalized
                        + ". Editing them is off (kb.projects[].untracked-edit-enabled).");
    }

    /**
     * The two refusals every write shares, whatever the file's state: the path may be written to at
     * all, and the content is small enough to be served back afterwards.
     *
     * <p>Named separately from {@link #requireCreatable} and {@link #requireReplaceable} because
     * {@code runScript} needs exactly this pair, and neither of the others, for a file the same run
     * has already staged: such a file is on no disk and in no index yet, so "must exist" and "must
     * be tracked" are both wrong questions to ask about it.
     *
     * @return the normalized path, as the write will spell it
     */
    String requireWritable(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = validateWritablePath(filePath);
        if (content.length > RepoFiles.MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Content too large (max "
                            + RepoFiles.MAX_FILE_SIZE / 1024
                            + " KB): "
                            + normalized);
        }
        return normalized;
    }

    /**
     * Path validation shared by write operations: same character/traversal rules as reads, plus
     * {@code .git/} internals and junk artefacts are never writable.
     */
    private String validateWritablePath(String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        paths.confine(normalized);
        if (RepoPaths.isInsideGitDir(normalized)) {
            throw new IllegalArgumentException("Writing into .git is not allowed");
        }
        if (RepoPaths.isJunkFile(normalized)) {
            throw new IllegalArgumentException("Refusing to create junk file: " + normalized);
        }
        return normalized;
    }

    // ── Disk and index ──────────────────────────────────────────────────────

    /**
     * Stages a path that was just written.
     *
     * <p>Not just cosmetics for an edit: a same-size edit written within the same clock tick is
     * "racily clean" and JGit's status (unlike native git) can miss it entirely — the index update
     * makes the change deterministically visible to {@code getUncommittedChanges}. It also matches
     * {@link #createFile}: everything the model changed is staged, ready for user review.
     */
    private void stage(String normalized) {
        try {
            git.add().addFilepattern(normalized).call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to stage file: " + normalized, e);
        }
    }

    /**
     * Drops a path from the index ({@code git rm --cached}) — the staging counterpart of a
     * deletion, and the reason a created-then-deleted file leaves no trace in {@code
     * getUncommittedChanges}: it was staged by {@link #createFile} and HEAD has never seen it.
     */
    private void unstage(String normalized) {
        try {
            git.rm().setCached(true).addFilepattern(normalized).call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to unstage file: " + normalized, e);
        }
    }

    /**
     * Stages an edited file only when it is tracked. An untracked file admitted by the project's
     * {@code allow-globs} must stay untracked through an edit — staging it would silently promote a
     * deliberately-uncommitted file into the next commit.
     */
    private void stageIfTracked(String normalized, boolean tracked) {
        if (tracked) {
            stage(normalized);
        }
    }

    private void writeAtomically(String relativePath, String content) {
        writeAtomically(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes via a temp file + atomic move so a crash never leaves a half-written file. */
    private void writeAtomically(String relativePath, byte[] content) {
        Path target = paths.resolve(relativePath);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), ".kb-edit-", ".tmp");
            Files.write(tmp, content);
            // The move replaces the target's inode, so without this the edited file would end up
            // with the temp file's default mode (0600) — silently dropping e.g. the executable
            // bit of a script. Copy the original permissions onto the temp file before the swap.
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows) — permissions are not inode-bound there.
            }
            try {
                Files.move(
                        tmp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
            throw new IllegalStateException("Cannot write file: " + relativePath, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to clean up {}", path, e);
        }
    }
}
