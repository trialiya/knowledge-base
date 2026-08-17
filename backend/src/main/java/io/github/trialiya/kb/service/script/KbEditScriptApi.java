package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileInfo;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/**
 * {@link KbScriptApi} plus the write methods, bound as {@code kb} only when {@code
 * ScriptEditPolicy} says writes are available.
 *
 * <p>A separate class rather than a runtime flag on the base one: {@code HostAccess.EXPLICIT}
 * exposes the methods of whichever object is bound, so with writes off {@code kb.edit} is not a
 * method that refuses — it does not exist. A model cannot spend attempts on a method it cannot see,
 * and the handbook it is given is assembled from the same policy.
 *
 * <p>Text and bytes are written by different methods on purpose. {@link #edit} matches an exact
 * fragment, which is what makes a model quote content it has really seen; bytes cannot carry that
 * evidence, so {@link #writeBytes} replaces a file whole and keeps the read rule as its only guard.
 *
 * <p><b>Nothing here touches disk.</b> Every method only updates the run's pending writes (see
 * {@code ScriptSession}); {@code ScriptRunner} writes the accumulated result once the script has
 * finished successfully. A script that edits twenty files and then throws — or is stopped by the
 * user, or runs out of budget — leaves the working tree untouched.
 */
public final class KbEditScriptApi extends KbScriptApi {

    private final GitService gitService;
    private final ScriptSession session;

    public KbEditScriptApi(
            GitService gitService, DocumentService documentService, ScriptSession session) {
        super(gitService, documentService, session);
        this.gitService = gitService;
        this.session = session;
    }

    /**
     * Replaces {@code oldString} with {@code newString} in a tracked file.
     *
     * <p>Same contract as the {@code editFile} tool, and for the same reason: the match is exact
     * and must be unique, so the script has to quote real current content rather than what it
     * assumes is there. Matching happens against the file as this run has it — a second edit of the
     * same file sees the first one.
     */
    @HostAccess.Export
    public Object edit(String path, String oldString, String newString) {
        return edit(path, oldString, newString, false);
    }

    /** As {@link #edit(String, String, String)}; {@code replaceAll} allows several occurrences. */
    @HostAccess.Export
    public Object edit(String path, String oldString, String newString, boolean replaceAll) {
        session.chargeCall();
        // Canonical from here down: the session keys its pending text on this string, and two
        // spellings of one file would otherwise stage two writes, the second computed from the
        // file on disk — silently discarding the first.
        String canonical = canonical(path);
        session.requireVisible(canonical);
        session.requireRead(canonical);
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty: " + canonical);
        }
        if (oldString.equals(newString)) {
            throw new IllegalArgumentException(
                    "oldString and newString are identical: " + canonical);
        }

        String text = currentText(canonical);
        String oldLf = oldString.replace("\r\n", "\n");
        String newLf = newString.replace("\r\n", "\n");
        int occurrences = countOccurrences(text, oldLf);
        if (occurrences == 0) {
            throw new IllegalArgumentException(
                    "oldString not found in "
                            + canonical
                            + ". Re-read the current content with kb.read and pass an exact,"
                            + " character-for-character fragment including whitespace.");
        }
        if (occurrences > 1 && !replaceAll) {
            throw new IllegalArgumentException(
                    "oldString occurs "
                            + occurrences
                            + " times in "
                            + canonical
                            + ". Extend it with surrounding lines to make it unique, or pass"
                            + " replaceAll=true as the fourth argument.");
        }

        String updated = replaceAll ? text.replace(oldLf, newLf) : replaceFirst(text, oldLf, newLf);
        session.stageEdit(canonical, updated);
        return result(canonical, "edit", occurrences);
    }

    /**
     * Creates a new file. Refused if the path already exists in this run's view of the tree — an
     * existing file is edited, not recreated, and the exact-match contract of {@link #edit} is what
     * keeps that honest.
     */
    @HostAccess.Export
    public Object create(String path, String content) {
        session.chargeCall();
        String canonical = canonical(path);
        session.requireVisible(canonical);
        if (session.pending(canonical).isPresent()) {
            throw new IllegalArgumentException(
                    "File already staged for writing in this run: " + canonical + ". Use kb.edit.");
        }
        if (gitService.exists(canonical)) {
            throw new IllegalArgumentException(
                    "File already exists: " + canonical + ". Use kb.edit to modify it.");
        }
        // Refused now rather than at apply time: a path createFile could never write (.git/, a
        // junk name, oversized content) does not become writable by waiting, and finding out
        // during the apply step would leave the run's earlier files on disk.
        gitService.requireCreatable(canonical, content);
        session.stageCreate(canonical, content);
        return result(canonical, "create", 1);
    }

    /**
     * Replaces the whole content of a binary file with raw bytes — the binary counterpart of {@link
     * #edit}, and the only write that can produce content which is not text.
     *
     * <p>Whole-content, not a fragment: {@link #edit}'s exact-match contract is what forces a
     * script to quote what is really in a file, and bytes at an offset carry no such evidence. The
     * read rule still holds — a file has to have been looked at ({@code kb.readBytes}, {@code
     * kb.read}, a {@code kb.grep} match, or another tool earlier in the same response) before this
     * run may overwrite it.
     *
     * <p><b>Binary targets only</b>, which is the other half of that argument. On a text file this
     * would be a whole-file replacement with no exact match behind it and no line diff in front of
     * it — the user would review a change shown as "binary files differ" — so a text file is
     * refused and {@link #edit} is named instead. The exception is a file this run created itself:
     * the script authored every byte of it, so there is nothing it could be overwriting unseen.
     *
     * @param data base64 (what {@code kb.readBase64} returns) or an array of byte values (what
     *     {@code kb.readBytes} returns)
     */
    @HostAccess.Export
    public Object writeBytes(String path, @Nullable Value data) {
        session.chargeCall();
        String canonical = canonical(path);
        session.requireVisible(canonical);
        session.requireRead(canonical);
        byte[] bytes = toBytes(data, "kb.writeBytes");
        // Refused now rather than at apply time, exactly as kb.create validates its path: a path
        // that cannot be written does not become writable by waiting, and finding out during the
        // apply step would leave the run's earlier files on disk.
        // Created by this run, so it is on no disk and in no index yet: the tracked check would
        // refuse the very file the script has just created, and there is no existing content for
        // the binary rule to protect. Staged as an *edit* is not the same thing — that file is on
        // disk, and rewriting it whole is exactly what the rule below is for.
        if (session.pending(canonical).filter(ScriptSession.PendingWrite::created).isPresent()) {
            gitService.requireWritable(canonical, bytes);
        } else {
            requireBinaryTarget(canonical);
            gitService.requireReplaceable(canonical, bytes);
        }
        session.stageBinaryEdit(canonical, bytes);
        return bytesResult(canonical, "write", bytes.length);
    }

    /** Refuses a text file, naming the method that edits one — see {@link #writeBytes}. */
    private void requireBinaryTarget(String canonical) {
        // Also the tracked check: an untracked path is "File not found" here, before any of the
        // run's writes have touched disk.
        GitFileInfo info = gitService.getFileInfo(canonical);
        // An empty file sniffs as text and is neither: there is no content to replace unseen and
        // no diff to lose, so a placeholder committed empty can still be filled with bytes.
        if (!info.binary() && info.sizeBytes() > 0) {
            throw new IllegalArgumentException(
                    "Refusing to overwrite "
                            + canonical
                            + " with raw bytes: it is a text file, and replacing one whole leaves"
                            + " the user a change with no diff to review. Change it with"
                            + " kb.edit(path, oldString, newString), which has to match real"
                            + " current content.");
        }
    }

    /**
     * Creates a new file from raw bytes — {@link #create} for content that is not text. Refused, as
     * {@link #create} is, if the path already exists in this run's view of the tree.
     *
     * @param data base64 or an array of byte values — see {@link #writeBytes}
     */
    @HostAccess.Export
    public Object createBytes(String path, @Nullable Value data) {
        session.chargeCall();
        String canonical = canonical(path);
        session.requireVisible(canonical);
        if (session.pending(canonical).isPresent()) {
            throw new IllegalArgumentException(
                    "File already staged for writing in this run: "
                            + canonical
                            + ". Use kb.writeBytes.");
        }
        if (gitService.exists(canonical)) {
            throw new IllegalArgumentException(
                    "File already exists: " + canonical + ". Use kb.writeBytes to overwrite it.");
        }
        byte[] bytes = toBytes(data, "kb.createBytes");
        gitService.requireCreatable(canonical, bytes);
        session.stageBinaryCreate(canonical, bytes);
        return bytesResult(canonical, "create", bytes.length);
    }

    /**
     * The bytes a script passed to a write, in either of the two shapes its own reads hand back:
     * base64 from {@code kb.readBase64}, or an array of byte values from {@code kb.readBytes}. Both
     * are accepted because both are natural — base64 survives a round trip through the script's
     * return value, an array is what byte-level work actually produces.
     */
    private byte[] toBytes(@Nullable Value data, String method) {
        if (data != null && !data.isNull()) {
            if (data.isString()) {
                try {
                    return Base64.getDecoder().decode(data.asString());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            method
                                    + ": the string is not valid base64. Pass base64 (as"
                                    + " kb.readBase64 returns) or an array of byte values (as"
                                    + " kb.readBytes returns).");
                }
            }
            if (data.hasArrayElements()) {
                return arrayToBytes(data, method);
            }
        }
        throw new IllegalArgumentException(
                method
                        + ": content must be a base64 string or an array of byte values (0..255),"
                        + " not "
                        + (data == null ? "null" : data));
    }

    private byte[] arrayToBytes(Value data, String method) {
        long size = data.getArraySize();
        // Checked before allocating: the write budget would refuse this content anyway, and a
        // script that built a nonsense-sized array should not have it copied onto the host heap
        // first.
        if (size > session.maxWriteBytes()) {
            throw new IllegalArgumentException(
                    method
                            + ": "
                            + size
                            + " bytes is more than one run may write ("
                            + session.maxWriteBytes()
                            + ").");
        }
        byte[] bytes = new byte[(int) size];
        for (int i = 0; i < bytes.length; i++) {
            Value element = data.getArrayElement(i);
            int value = element.isNumber() && element.fitsInInt() ? element.asInt() : -1;
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException(
                        method
                                + ": element "
                                + i
                                + " is "
                                + element
                                + ", not a byte value (0..255).");
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    /**
     * The file as this run sees it: its pending version if already written, else from disk.
     *
     * <p>The truncation check is load-bearing. {@code getFileContent} answers a file over 512 KB
     * with a head+tail excerpt, and writing an edited excerpt back would silently delete everything
     * between — so an oversized file is refused outright, exactly as the {@code editFile} tool
     * refuses it.
     */
    private String currentText(String path) {
        return switch (session.pending(path).orElse(null)) {
            case null -> readFullText(path);
            case ScriptSession.TextWrite text -> text.text();
            // The staged bytes are this run's own doing, so there is nothing to re-read: the file
            // the script would be editing as text no longer exists in this run, not even on disk.
            case ScriptSession.BinaryWrite _ ->
                    throw new IllegalArgumentException(
                            "Cannot edit "
                                    + path
                                    + " as text: this run already wrote raw bytes to it. Rewrite"
                                    + " it whole with kb.writeBytes.");
        };
    }

    private String readFullText(String path) {
        GitFileContent content = gitService.getFileContent(path);
        if (content.binary()) {
            throw new IllegalArgumentException(
                    "Cannot edit "
                            + path
                            + " as text: it is a binary file. Rewrite it whole with"
                            + " kb.writeBytes(path, data).");
        }
        if (content.truncated() || content.content() == null) {
            throw new IllegalArgumentException(
                    "File too large to edit: "
                            + path
                            + " ("
                            + content.sizeBytes()
                            + " bytes). Only files small enough to be read whole can be edited.");
        }
        return content.content();
    }

    private static Object result(String path, String operation, int occurrences) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("operation", operation);
        row.put("occurrences", occurrences);
        // Deliberately no diff here: the run's real diffs come back in ScriptResult.edits, computed
        // from what was actually written, not from what the script believed it was writing.
        return ProxyObject.fromMap(row);
    }

    /** As {@link #result}, for a byte write: what it wrote is a size, never an occurrence count. */
    private static Object bytesResult(String path, String operation, int bytes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("operation", operation);
        row.put("bytes", bytes);
        return ProxyObject.fromMap(row);
    }

    private static String replaceFirst(String text, String target, String replacement) {
        int index = text.indexOf(target);
        return text.substring(0, index) + replacement + text.substring(index + target.length());
    }

    private static int countOccurrences(@Nullable String text, String needle) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
