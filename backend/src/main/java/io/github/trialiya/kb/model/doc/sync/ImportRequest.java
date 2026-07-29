package io.github.trialiya.kb.model.doc.sync;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What to apply out of a comparison.
 *
 * <p>The client sends back the {@link SyncEntry#path()}s it ticked, never a whole plan: the server
 * re-runs the comparison at apply time and acts only on the paths that are still in the requested
 * set. A file that changed between the compare and the apply is therefore re-read, not replayed
 * from a stale snapshot.
 *
 * @param parentId subtree the export folder maps onto, {@code null} for the tree root
 * @param paths selected node paths; {@code null} or empty means "everything actionable"
 * @param deleteMissing whether {@link SyncStatus#MISSING} selections actually delete the node and
 *     its subtree — off by default, because an import that silently deletes is not an import
 */
public record ImportRequest(
        @Nullable Long parentId, @Nullable List<String> paths, boolean deleteMissing) {

    /** {@code null} when the caller did not narrow the selection. */
    public @Nullable Set<String> selection() {
        return paths == null || paths.isEmpty() ? null : Set.copyOf(paths);
    }
}
