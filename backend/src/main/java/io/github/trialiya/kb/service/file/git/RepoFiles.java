package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reading file bytes off the working tree, and the two questions everything asks of them: is this
 * binary, and what does it say as text.
 *
 * <p>Shared by the read tools and the write tools — an edit has to know a file is text before it
 * may touch it, on the same terms the reader used to serve it.
 */
final class RepoFiles {

    /**
     * The largest file served or written whole: bigger ones come back as a head+tail excerpt from
     * {@code getFileContent}, and no write may produce one.
     */
    static final long MAX_FILE_SIZE = 512 * 1024;

    /** Bytes inspected when sniffing for binary content (a NUL byte ⇒ binary). */
    static final int BINARY_SNIFF_BYTES = 8192;

    private RepoFiles() {}

    static long sizeOf(String normalized, Path absolute) {
        try {
            return Files.size(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file size: " + normalized, e);
        }
    }

    static byte[] readAll(String normalized, Path absolute) {
        try {
            return Files.readAllBytes(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
    }

    /**
     * {@code length} bytes starting at {@code offset}, or fewer when the file ends first — so a
     * caller may ask for more than is there (the binary sniff does) without checking the size.
     */
    static byte[] readWindow(String normalized, Path absolute, long offset, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(absolute)) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read() fills what it can per call; loop until the window or the file ends.
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
        return buffer.position() == length
                ? buffer.array()
                : Arrays.copyOf(buffer.array(), buffer.position());
    }

    /**
     * File bytes as text: UTF-8, with CRLF normalised to LF so a Windows working-tree file does not
     * leave a {@code \r} at the end of every line for the caller to trip over.
     */
    static String decodeToLf(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * Heuristic binary detection matching Git's own behaviour: a file is treated as binary if a NUL
     * byte appears within the first {@value #BINARY_SNIFF_BYTES} bytes. Cheap and allocation-free,
     * and accurate for the source/text files an AI assistant is asked to read.
     */
    static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
