package io.github.trialiya.kb.model.doc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type of a knowledge-base node. Persisted (and exposed over JSON) as a lowercase string —
 * {@code "document"} / {@code "folder"} — to stay backward-compatible with existing rows and the
 * frontend contract.
 *
 * <p>Persistence: a dedicated Spring Data JDBC converter pair (see {@code DocumentTypeJdbcConverter})
 * maps this enum to/from the lowercase {@link #getValue() value}. JSON: {@link JsonValue} /
 * {@link JsonCreator} keep the same lowercase representation on the wire.
 */
public enum DocumentType {
    DOCUMENT("document"),
    FOLDER("folder");

    private final String value;

    DocumentType(String value) {
        this.value = value;
    }

    /** Lowercase wire/DB representation, e.g. {@code "folder"}. */
    @JsonValue
    public String getValue() {
        return value;
    }

    public boolean isFolder() {
        return this == FOLDER;
    }

    /**
     * Lenient parse: accepts the lowercase value or the enum name (any case). Unknown / blank input
     * falls back to {@link #DOCUMENT}, matching the previous string-based behaviour.
     */
    @JsonCreator
    public static DocumentType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return DOCUMENT;
        }
        String normalized = raw.trim();
        for (DocumentType t : values()) {
            if (t.value.equalsIgnoreCase(normalized) || t.name().equalsIgnoreCase(normalized)) {
                return t;
            }
        }
        return DOCUMENT;
    }
}
