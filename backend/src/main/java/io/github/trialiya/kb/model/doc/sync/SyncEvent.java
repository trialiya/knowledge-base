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
 * <p>{@code summary} is deliberately untyped: the compare and the import stream the same frame
 * shape and differ only in what their final tally counts ({@link DiffSummary} vs {@link
 * ImportSummary}). Nulls are omitted, so a frame stays a handful of bytes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncEvent(
        Type type,
        int processed,
        @Nullable String path,
        @Nullable SyncEntry entry,
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
        return new SyncEvent(Type.ENTRY, processed, entry.path(), entry, null, null);
    }

    public static SyncEvent progress(int processed, String path) {
        return new SyncEvent(Type.PROGRESS, processed, path, null, null, null);
    }

    public static SyncEvent done(int processed, Object summary) {
        return new SyncEvent(Type.DONE, processed, null, null, summary, null);
    }

    public static SyncEvent error(String message) {
        return new SyncEvent(Type.ERROR, 0, null, null, null, message);
    }
}
