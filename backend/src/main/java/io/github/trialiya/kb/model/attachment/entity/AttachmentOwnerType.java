package io.github.trialiya.kb.model.attachment.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AttachmentOwnerType {
    DOCUMENT("document"),
    CHAT("chat");

    private final String value;

    AttachmentOwnerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AttachmentOwnerType fromValue(String value) {
        for (AttachmentOwnerType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AttachmentOwnerType: " + value);
    }
}
