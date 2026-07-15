package io.github.trialiya.kb.model.embedding;

public enum EmbeddingEntityType {
    DOCUMENT("document"),
    ATTACHMENT("attachment");

    private final String value;

    EmbeddingEntityType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EmbeddingEntityType fromValue(String value) {
        for (EmbeddingEntityType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EmbeddingEntityType: " + value);
    }
}
