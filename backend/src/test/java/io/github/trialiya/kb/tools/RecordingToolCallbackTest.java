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
    void concatenatedObjectsBecomeEmptyObject() {
        // Both objects are well-formed on their own, so a lenient parser reads the first one and
        // drops the rest — the string still goes to the provider whole, and the provider rejects
        // it. Everything after the first value has to fail the check.
        assertThat(RecordingToolCallback.sanitizeArguments("{\"filePath\": \"a\"}{\"q\": \"b\"}"))
                .isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("{} {}")).isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("{\"q\": \"a\"} trailing"))
                .isEqualTo("{}");
    }

    @Test
    void nonObjectJsonBecomesEmptyObject() {
        assertThat(RecordingToolCallback.sanitizeArguments("[1, 2]")).isEqualTo("{}");
        assertThat(RecordingToolCallback.sanitizeArguments("42")).isEqualTo("{}");
    }

    @Test
    void parseToolInputToleratesTheSameMalformedInput() {
        assertThat(RecordingToolCallback.parseToolInput("{\"filePath\": \"a\"{}"))
                .isEqualTo(Map.of());
    }
}
