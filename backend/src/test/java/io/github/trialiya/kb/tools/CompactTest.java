package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link Compact#oneLine} — a log entry must stay one bounded line. */
class CompactTest {

    @Test
    void keepsShortTextAsIs() {
        assertThat(Compact.oneLine("ok", 10)).isEqualTo("ok");
    }

    @Test
    void truncatesLongText() {
        assertThat(Compact.oneLine("abcdefghij", 4)).isEqualTo("abcd…(+6)");
    }

    @Test
    void foldsLineBreaksIntoOneLine() {
        assertThat(Compact.oneLine("first\n  second\r\nthird", 100))
                .isEqualTo("first ⏎ second ⏎ third");
    }

    @Test
    void nullStaysNull() {
        assertThat(Compact.oneLine(null, 10)).isNull();
    }
}
