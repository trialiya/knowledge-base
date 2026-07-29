package io.github.trialiya.kb.model.doc.sync;

/**
 * Tally of a comparison run, one counter per {@link SyncStatus}. Sent as the {@code summary} of the
 * stream's final frame so the UI can label the "import selected" button without counting rows.
 */
public record DiffSummary(int added, int modified, int unchanged, int missing) {

    public int total() {
        return added + modified + unchanged + missing;
    }
}
