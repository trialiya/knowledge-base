package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Где пробелы — синтаксис, и {@code -w} спрятал бы правку, менявшую поведение. */
class BlameMatchingTest {

    @Test
    void indentationIsSyntaxInPythonYamlAndMakefiles() {
        assertThat(BlameMatching.whitespaceMatters("app/billing.py")).isTrue();
        assertThat(BlameMatching.whitespaceMatters(".github/workflows/CI.YML")).isTrue();
        assertThat(BlameMatching.whitespaceMatters("Makefile")).isTrue();
        assertThat(BlameMatching.whitespaceMatters("tools/build.mk")).isTrue();
    }

    @Test
    void elsewhereItIsNot() {
        assertThat(BlameMatching.whitespaceMatters("src/Billing.java")).isFalse();
        assertThat(BlameMatching.whitespaceMatters("docs/py")).isFalse();
        assertThat(BlameMatching.whitespaceMatters(".yml")).isFalse();
        assertThat(BlameMatching.whitespaceMatters("yaml/README.md")).isFalse();
    }
}
