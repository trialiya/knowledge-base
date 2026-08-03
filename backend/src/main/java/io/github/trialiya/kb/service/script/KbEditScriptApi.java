package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/**
 * {@link KbScriptApi} plus the two write methods, bound as {@code kb} only when {@code
 * ScriptEditPolicy} says writes are available.
 *
 * <p>A separate class rather than a runtime flag on the base one: {@code HostAccess.EXPLICIT}
 * exposes the methods of whichever object is bound, so with writes off {@code kb.edit} is not a
 * method that refuses — it does not exist. A model cannot spend attempts on a method it cannot see,
 * and the handbook it is given is assembled from the same policy.
 *
 * <p><b>Nothing here touches disk.</b> Both methods only update the run's pending text (see {@code
 * ScriptSession}); {@code ScriptRunner} writes the accumulated result once the script has finished
 * successfully. A script that edits twenty files and then throws — or is stopped by the user, or
 * runs out of budget — leaves the working tree untouched.
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
        if (session.pendingText(canonical).isPresent()) {
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
     * The file as this run sees it: its pending version if already written, else from disk.
     *
     * <p>The truncation check is load-bearing. {@code getFileContent} answers a file over 512 KB
     * with a head+tail excerpt, and writing an edited excerpt back would silently delete everything
     * between — so an oversized file is refused outright, exactly as the {@code editFile} tool
     * refuses it.
     */
    private String currentText(String path) {
        return session.pendingText(path).orElseGet(() -> readFullText(path));
    }

    private String readFullText(String path) {
        GitFileContent content = gitService.getFileContent(path);
        if (content.binary()) {
            throw new IllegalArgumentException("Cannot edit a binary file: " + path);
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
