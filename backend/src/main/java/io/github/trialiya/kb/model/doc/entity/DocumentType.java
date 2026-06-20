package io.github.trialiya.kb.model.doc.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DocumentType {
    DOCUMENT("document"),
    FOLDER("folder");

    private final String value;

    DocumentType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DocumentType fromValue(String value) {
        for (DocumentType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return DOCUMENT;
    }
}
