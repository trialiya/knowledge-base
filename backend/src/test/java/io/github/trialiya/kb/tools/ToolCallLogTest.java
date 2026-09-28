package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ToolCallLog#brief} — the short INFO form must stay one bounded line. */
class ToolCallLogTest {

    @Test
    void keepsShortTextAsIs() {
        assertThat(ToolCallLog.brief("ok", 10)).isEqualTo("ok");
    }

    @Test
    void truncatesLongText() {
        assertThat(ToolCallLog.brief("abcdefghij", 4)).isEqualTo("abcd…(+6)");
    }

    @Test
    void foldsLineBreaksIntoOneLine() {
        assertThat(ToolCallLog.brief("first\n  second\r\nthird", 100))
                .isEqualTo("first ⏎ second ⏎ third");
    }

    @Test
    void nullStaysNull() {
        assertThat(ToolCallLog.brief(null, 10)).isNull();
    }
}
