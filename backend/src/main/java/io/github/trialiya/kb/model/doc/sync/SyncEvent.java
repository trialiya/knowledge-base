package io.github.trialiya.kb.model.doc.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * One frame of an export / compare / import event stream.
 *
 * <p>The whole point of these operations being streamed is that the client sees the tree being
 * walked instead of a spinner: a comparison emits an {@link Type#ENTRY} per node as it is decided,
 * an import emits {@link Type#PROGRESS} per node as it is written, and both close with {@link
 * Type#DONE} carrying the totals.
 *
 * <p>An import's {@link Type#PROGRESS} frames additionally carry {@link SyncAction} — and, for a
 * node that was skipped, the reason. Those are what a client assembles the run's log from: the
 * final tally says three nodes failed, the frames say which three and why. The export's own
 * progress carries no action; it does one thing to every node, and the file count already says how
 * many times.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncEvent(
        Type type,
        int processed,
        @Nullable String path,
        @Nullable SyncEntry entry,
        @Nullable SyncAction action,
        @Nullable Object summary,
        @Nullable String message) {

    public enum Type {
        /** A compared node. */
        ENTRY,
        /** A node the import just wrote. */
        PROGRESS,
        /** Terminal frame, carries the summary. */
        DONE,
        /** Terminal frame, carries a human-readable reason. */
        ERROR
    }

    public static SyncEvent entry(int processed, SyncEntry entry) {
        return new SyncEvent(Type.ENTRY, processed, entry.path(), entry, null, null, null);
    }

    /** Progress with nothing to say about the node beyond having reached it — the export. */
    public static SyncEvent progress(int processed, String path) {
        return new SyncEvent(Type.PROGRESS, processed, path, null, null, null, null);
    }

    /** Progress that names what was done — one line of an import's log. */
    public static SyncEvent progress(int processed, String path, SyncAction action) {
        return new SyncEvent(Type.PROGRESS, processed, path, null, action, null, null);
    }

    /** A node the import gave up on, with the reason it did. */
    public static SyncEvent failure(int processed, String path, @Nullable String message) {
        return new SyncEvent(
                Type.PROGRESS, processed, path, null, SyncAction.FAILED, null, message);
    }

    public static SyncEvent done(int processed, Object summary) {
        return new SyncEvent(Type.DONE, processed, null, null, null, summary, null);
    }

    public static SyncEvent error(String message) {
        return new SyncEvent(Type.ERROR, 0, null, null, null, null, message);
    }
}
