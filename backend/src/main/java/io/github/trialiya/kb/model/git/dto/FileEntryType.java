package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FileEntryType {
    FILE("file"),
    DIRECTORY("directory");

    private final String value;

    FileEntryType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static FileEntryType create(String value) {
        return fromValue(value);
    }

    public static FileEntryType fromValue(String value) {
        for (FileEntryType type : FileEntryType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown file entry type: " + value);
    }
}
