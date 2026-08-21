package io.github.trialiya.kb.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.doc.dto.DocumentGrepMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The document-side grep: which lines match, how they are grouped and numbered, and which section
 * each block is reported in — the two things a model does with the answer are quoting it and
 * addressing an edit with it.
 */
class DocumentGrepTest {

    private static final long DOC_ID = 7L;
    private static final String TITLE = "Гайд";
    private static final String MD =
            """
            # Гайд
            вступление про Docker
            ## Установка
            ставим Docker
            и ещё раз docker
            ## FAQ
            без совпадений
            """;

    private static List<DocumentGrepMatch> grep(String pattern, boolean regex, int ctx, int limit) {
        return DocumentGrep.matches(
                DOC_ID, TITLE, MD, DocumentGrep.compile(pattern, regex), ctx, limit);
    }

    @Test
    void matchesAreCaseInsensitiveAndCarryRealLineNumbers() {
        List<DocumentGrepMatch> matches = grep("DOCKER", false, 0, 50);

        assertThat(matches).extracting(DocumentGrepMatch::matchLine).containsExactly(2, 4, 5);
        assertThat(matches)
                .extracting(DocumentGrepMatch::text)
                .containsExactly("вступление про Docker", "ставим Docker", "и ещё раз docker");
        assertThat(matches).allSatisfy(m -> assertThat(m.documentId()).isEqualTo(DOC_ID));
    }

    @Test
    void sectionPathPointsAtTheSectionToEdit() {
        List<DocumentGrepMatch> matches = grep("docker", false, 0, 50);

        assertThat(matches)
                .extracting(DocumentGrepMatch::sectionPath)
                .containsExactly("Гайд", "Гайд > Установка", "Гайд > Установка");
    }

    @Test
    void contextLinesFoldOverlappingHitsIntoOneMarkedBlock() {
        List<DocumentGrepMatch> matches = grep("docker", false, 1, 50);

        // All three hits sit within one line of each other, so their context windows form a single
        // fragment; the markup is git grep's own: ":N:" for a match, "-N-" for context.
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().matchLine()).isEqualTo(2);
        assertThat(matches.getFirst().text())
                .isEqualTo(
                        """
                        -1-# Гайд
                        :2:вступление про Docker
                        -3-## Установка
                        :4:ставим Docker
                        :5:и ещё раз docker
                        -6-## FAQ
                        """);
    }

    @Test
    void regexIsOptInAndLiteralIsTakenLiterally() {
        assertThat(grep("Docker|FAQ", true, 0, 50))
                .extracting(DocumentGrepMatch::matchLine)
                .containsExactly(2, 4, 5, 6);
        assertThat(grep("Docker|FAQ", false, 0, 50)).isEmpty();
    }

    @Test
    void limitStopsAtTheRequestedNumberOfBlocks() {
        assertThat(grep("docker", false, 0, 2)).hasSize(2);
    }

    @Test
    void brokenRegexNamesTheSyntaxErrorAndTheWayOut() {
        assertThatThrownBy(() -> DocumentGrep.compile("(unclosed", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regex=false");
    }

    @Test
    void documentWithoutSectionsStillMatches() {
        List<DocumentGrepMatch> matches =
                DocumentGrep.matches(
                        DOC_ID,
                        TITLE,
                        "просто текст\n",
                        DocumentGrep.compile("текст", false),
                        0,
                        5);

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().sectionPath()).isEqualTo("_preamble");
    }
}
