package io.github.trialiya.kb.model.doc.sync;

import io.github.trialiya.kb.model.doc.entity.DocumentType;
import org.jspecify.annotations.Nullable;

/**
 * One line of a comparison between the export folder and the database.
 *
 * <p>{@link #path} is the identity: the {@code /}-joined chain of safe names the export gives a
 * node, without extension. It is what the client sends back to say "import this one", so it has to
 * mean the same thing on both sides — see {@code DocumentTreeReader}.
 *
 * @param path export-relative node path, e.g. {@code modeli-dannykh/dokumenty}
 * @param title human title — from {@code .index.md} on the disk side, from the row otherwise
 * @param type folder or document
 * @param status what importing this entry would do
 * @param docId database id, {@code null} for entries that exist only on disk
 * @param depth nesting level, so the client can indent without re-parsing paths
 */
public record SyncEntry(
        String path,
        String title,
        DocumentType type,
        SyncStatus status,
        @Nullable Long docId,
        int depth) {

    public static SyncEntry of(
            String path,
            String title,
            DocumentType type,
            SyncStatus status,
            @Nullable Long docId,
            int depth) {
        return new SyncEntry(path, title, type, status, docId, depth);
    }

    /** True when applying this entry would write to the database. */
    public boolean isActionable() {
        return status != SyncStatus.UNCHANGED;
    }
}
