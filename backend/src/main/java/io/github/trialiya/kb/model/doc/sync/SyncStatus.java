package io.github.trialiya.kb.model.doc.sync;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How one export path compares between the file system and the database. */
public enum SyncStatus {

    /** On disk only — importing it creates a new node. */
    ADDED,

    /** On both sides, bodies differ — importing it overwrites the node's description. */
    MODIFIED,

    /** On both sides, bodies identical — nothing to do. */
    UNCHANGED,

    /** In the database only — the export folder no longer has it. */
    MISSING;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
