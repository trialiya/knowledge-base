package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitFileBytes;
import io.github.trialiya.kb.model.git.dto.GitFileInfo;
import io.github.trialiya.kb.service.file.outline.LanguageDetector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.jgit.lib.Repository;

/**
 * A served file's raw bytes, binary files included — what {@code GitService.getFileContent}, which
 * decodes text, cannot hand out: windows for scripts, whole files for the browser's previews,
 * hashes. Every read goes through {@link VisibleFiles#require}, the same gate the text reads pass.
 */
final class RepoBytes {

    /**
     * Largest window {@link #window} will hand back in one call. Not a limit on the file:
     * bytes are read positionally, so anything bigger is read window by window — which is also the
     * only shape in which a caller can process a file larger than it can hold.
     */
    private static final long MAX_BYTE_WINDOW = 1024 * 1024;

    /**
     * Largest file {@link #whole} hands to the browser whole. Bigger than anything a picture
     * in a source tree has business being, and small enough that a request for the artefact
     * somebody committed next to it costs a refusal rather than the heap.
     */
    private static final long MAX_RAW_FILE_SIZE = 16L * 1024 * 1024;

    /**
     * Chunk size for reads that only pass bytes through, never keeping them ({@link #sha256}).
     */
    private static final int STREAM_BUFFER_BYTES = 8192;

    private final Repository repository;
    private final VisibleFiles visible;

    RepoBytes(Repository repository, VisibleFiles visible) {
        this.repository = repository;
        this.visible = visible;
    }

    /**
     * Size, binary flag and detected language of a tracked file, without reading its content.
     *
     * <p>The question {@code GitService.getFileContent} cannot answer cheaply: it has to read the file to
     * tell whether it is binary, and for a binary one everything it read is thrown away. Scripts
     * ask this first and only then decide what to read the file with, so it stays a stat plus an 8
     * KB sniff whatever the file's size.
     */
    GitFileInfo info(String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        Path absolute = visible.require(normalized).absolute();
        return new GitFileInfo(
                normalized,
                RepoFiles.sizeOf(normalized, absolute),
                RepoFiles.isBinary(RepoFiles.readWindow(normalized, absolute, 0, RepoFiles.BINARY_SNIFF_BYTES)),
                LanguageDetector.detect(normalized));
    }

    /**
     * Raw bytes of a tracked file, binary ones included — the byte-level counterpart of {@code
     * GitService.getFileContent}, which decodes as UTF-8 and therefore serves text only.
     *
     * <p>Only the requested window is read from disk, so the cost of the call follows {@code
     * length} rather than the size of the file: a caller stepping through a 200 MB archive never
     * materialises more than its own window. That is also why there is no truncation flag here — a
     * short window is what was asked for, not an excerpt substituted for the whole.
     *
     * @param offset first byte to return, 0-based; clamped to the end of the file
     * @param length how many bytes to return; {@code 0} or less means "to the end of the file"
     */
    GitFileBytes window(String filePath, long offset, long length) {
        String normalized = RepoPaths.normalize(filePath);
        Path absolute = visible.require(normalized).absolute();
        long size = RepoFiles.sizeOf(normalized, absolute);
        long from = Math.min(Math.max(offset, 0), size);
        long want = length > 0 ? Math.min(length, size - from) : size - from;
        if (want > MAX_BYTE_WINDOW) {
            throw new IllegalArgumentException("Cannot read "
                    + want
                    + " bytes at once (max "
                    + MAX_BYTE_WINDOW / 1024
                    + " KB): "
                    + normalized
                    + ". Read the file in windows (offset, length).");
        }
        byte[] window = RepoFiles.readWindow(normalized, absolute, from, (int) want);
        // The binary flag describes the file, not the window: it is defined on the head of the
        // file, so unless this window already covers that head — a short window at offset 0 does
        // not — the head is read again to answer it. Otherwise a four-byte peek at a file whose
        // first NUL sits at byte 100 would come back "not binary".
        boolean windowCoversHead = from == 0 && want >= Math.min(size, RepoFiles.BINARY_SNIFF_BYTES);
        byte[] head =
                windowCoversHead ? window : RepoFiles.readWindow(normalized, absolute, 0, RepoFiles.BINARY_SNIFF_BYTES);
        return new GitFileBytes(normalized, window, from, size, RepoFiles.isBinary(head));
    }

    /**
     * Whole file, for handing straight to the browser — the bytes behind an inline preview of an
     * image. Unlike {@link #window} there is no window here: a picture is shown or it is not,
     * and half of one is nothing at all.
     *
     * <p>Serves the same files every other read does (tracked, plus what the project's {@code
     * allow-globs} admit) and refuses anything over {@value #MAX_RAW_FILE_SIZE} bytes rather than
     * pulling it into memory — a repository holds artefacts as well as sources, and the caller gets
     * a refusal it can show instead of a browser hanging on a video-sized blob.
     *
     * <p>The file as of a commit is {@link #wholeAt}.
     *
     * @throws IllegalArgumentException if the file is bigger than that limit
     */
    GitFileBytes whole(String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        Path absolute = visible.require(normalized).absolute();
        long size = RepoFiles.sizeOf(normalized, absolute);
        requireServableSize(normalized, size);
        byte[] bytes = RepoFiles.readAll(normalized, absolute);
        return new GitFileBytes(normalized, bytes, 0, size, RepoFiles.isBinary(bytes));
    }

    /**
     * The file as of a commit — {@code git show <rev>:<path>}, so a snapshot of a revision shows the
     * picture that revision holds and not the one on disk.
     *
     * @param rev any revision git resolves: a full or short hash, a branch, a tag, {@code HEAD~2}
     * @throws IllegalArgumentException if the revision is unknown, holds no such file, or the file
     *     there is bigger than {@value #MAX_RAW_FILE_SIZE} bytes
     */
    GitFileBytes wholeAt(String rev, String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        // Предел уходит внутрь чтения: размер объекта известен до того, как он поднят в память, и
        // отказать по уже прочитанным байтам значило бы заплатить ровно то, ради чего предел есть.
        CommitFiles.Blob blob = CommitFiles.read(repository, rev.strip(), normalized, MAX_RAW_FILE_SIZE);
        return new GitFileBytes(normalized, blob.bytes(), 0, blob.size(), RepoFiles.isBinary(blob.bytes()));
    }

    private static void requireServableSize(String normalized, long size) {
        if (size > MAX_RAW_FILE_SIZE) {
            throw new IllegalArgumentException("File is too large to preview ("
                    + size
                    + " B, max "
                    + MAX_RAW_FILE_SIZE / (1024 * 1024)
                    + " MB): "
                    + normalized);
        }
    }

    /**
     * SHA-256 of a tracked file's bytes, lowercase hex.
     *
     * <p>Streamed, so it costs nothing in memory whatever the file's size — which is the point:
     * comparing two builds, or spotting that a fixture changed, otherwise means pulling both files
     * through a caller that only wants to know whether they differ.
     */
    String sha256(String filePath) {
        String normalized = RepoPaths.normalize(filePath);
        Path absolute = visible.require(normalized).absolute();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(absolute)) {
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
