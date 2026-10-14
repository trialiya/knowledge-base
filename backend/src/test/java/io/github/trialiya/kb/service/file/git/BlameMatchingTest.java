package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * «Кто написал строки»: {@code -M} всегда, {@code -w} — кроме файлов, где отступ — синтаксис и
 * {@code -w} спрятал бы правку, менявшую поведение.
 */
class BlameMatchingTest {

    @Test
    void indentationIsSyntaxInPythonYamlMakefilesAndBazel() {
        assertThat(BlameMatching.whitespaceMatters("app/billing.py")).isTrue();
        assertThat(BlameMatching.whitespaceMatters(".github/workflows/CI.YML")).isTrue();
        assertThat(BlameMatching.whitespaceMatters("Makefile")).isTrue();
        assertThat(BlameMatching.whitespaceMatters("lib/Makefile.am")).isTrue();
        assertThat(BlameMatching.whitespaceMatters("tools/BUILD.bazel")).isTrue();
    }

    /** Путь — как его написала модель: с обратными слешами и пробелами по краям. */
    @Test
    void theNameIsReadAsTheCallerSpelledIt() {
        assertThat(BlameMatching.whitespaceMatters("tools\\Makefile")).isTrue();
        assertThat(BlameMatching.whitespaceMatters(" app/charge.py ")).isTrue();
    }

    @Test
    void elsewhereItIsNot() {
        assertThat(BlameMatching.whitespaceMatters("src/Billing.java")).isFalse();
        assertThat(BlameMatching.whitespaceMatters("docs/py")).isFalse();
        assertThat(BlameMatching.whitespaceMatters(".yml")).isFalse();
        assertThat(BlameMatching.whitespaceMatters("yaml/README.md")).isFalse();
    }

    @Test
    void authorshipDropsOnlyTheWhitespaceSwitchWhereIndentationIsSyntax() {
        assertThat(BlameMatching.authorship("src/Billing.java")).isEqualTo(new BlameMatching(true, true));
        assertThat(BlameMatching.authorship("app/billing.py")).isEqualTo(new BlameMatching(false, true));
    }
}
