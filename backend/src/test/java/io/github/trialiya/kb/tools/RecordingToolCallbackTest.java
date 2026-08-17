package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RecordingToolCallback#sanitizeArguments} — the guard that keeps malformed tool-call JSON
 * (e.g. a model streaming two concatenated argument objects into one string) out of persisted
 * history. {@link RecordingToolCallback#parseToolInput} already tolerates bad JSON for the live
 * UI-meta path; these cases mirror the same inputs through the persistence-facing method.
 */
class RecordingToolCallbackTest {

    @Test
    void keepsValidJsonAsIs() {
        assertThat(RecordingToolCallback.sanitizeArguments("{\"q\": \"a\"}"))
                .isEqualTo("{\"q\": \"a\"}");
    }

    @Test
    void blankBecomesEmptyObject() {
        assertThat(RecordingToolCallback.sanitizeArguments(null)).isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("")).isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("   ")).isEqualTo("{}");
    }

    @Test
    void malformedJsonBecomesEmptyObject() {
        // The object's closing brace is missing, replaced by a second tool call's empty argument
        // object — the shape produced by a streaming client that mis-accumulates argument deltas
        // across tool calls sharing an index.
        assertThat(RecordingToolCallback.sanitizeArguments("{\"filePath\": \"a\"{}"))
                .isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("not json at all")).isEqualTo("{}");
    }

    @Test
    void parseToolInputToleratesTheSameMalformedInput() {
        assertThat(RecordingToolCallback.parseToolInput("{\"filePath\": \"a\"{}"))
                .isEqualTo(Map.of());
    }
}
