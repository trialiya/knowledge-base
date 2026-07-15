package io.github.trialiya.kb.model.embedding;

public enum EmbeddingTaskStatus {
    PENDING("pending"),
    STARTING("starting"),
    DONE("done"),
    FAILED("failed"),
    SUPERSEDED("superseded");

    private final String value;

    EmbeddingTaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EmbeddingTaskStatus fromValue(String value) {
        for (EmbeddingTaskStatus s : values()) {
            if (s.value.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown EmbeddingTaskStatus: " + value);
    }
}
