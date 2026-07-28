package io.github.trialiya.kb.model.doc.sync;

/**
 * Tally of an import run.
 *
 * @param created nodes that did not exist in the database
 * @param updated nodes whose description was overwritten from disk
 * @param deleted nodes removed because the export folder no longer has them (only when the caller
 *     asked for it, and only for paths it selected)
 * @param relinked nodes written a second time because their body contained links into the export
 *     that had to become {@code /?doc=ID} again — the only case that costs two writes
 * @param failed nodes skipped after an error; the run continues past them
 */
public record ImportSummary(int created, int updated, int deleted, int relinked, int failed) {}
