package io.github.trialiya.kb.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * App links an export flattens to text: an export is read outside the app, where {@code /files}
 * serves nothing, so a link has to keep what it cited — the path, and the commit when it named one.
 */
class DocumentLinkRewriterTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void aFileLinkKeepsItsPathAndDropsTheProjectAndRange() {
        assertThat(
                        DocumentLinkRewriter.flattenFileLinks(
                                "See [Git.java](/files?path=backend/Git.java&project=kb#L1-L10)."))
                .isEqualTo("See Git.java (backend/Git.java).");
    }

    @Test
    void aFileAsOfACommitNamesTheCommitBesideThePath() {
        assertThat(
                        DocumentLinkRewriter.flattenFileLinks(
                                "[B.java](/files?path=a/B.java&rev=" + HASH + "&project=kb#L3)"))
                .isEqualTo("B.java (a/B.java @ 0123456)");
        assertThat(DocumentLinkRewriter.flattenFileLinks("[B](/files?path=a/B.java&rev=abc1234)"))
                .isEqualTo("B (a/B.java @ abc1234)");
    }

    @Test
    void aPathThatMerelyContainsRevIsNotCutAtIt() {
        // Only a hex tail is the parameter; `&rev=draft` is part of an (unencoded) file name.
        assertThat(DocumentLinkRewriter.flattenFileLinks("[x](/files?path=notes&rev=draft.md)"))
                .isEqualTo("x (notes&rev=draft.md)");
    }

    @Test
    void aCommitLinkLabelledWithItsHashBecomesTheLabel() {
        assertThat(
                        DocumentLinkRewriter.flattenCommitLinks(
                                "fixed in [`0123456`](/files?rev=" + HASH + "&project=kb)"))
                .isEqualTo("fixed in `0123456`");
    }

    @Test
    void aCommitLinkWithProseKeepsTheHash() {
        assertThat(
                        DocumentLinkRewriter.flattenCommitLinks(
                                "[this commit](/files?rev=" + HASH + ")"))
                .isEqualTo("this commit (0123456)");
        // The address the app shows for a commit, pasted into a document.
        assertThat(
                        DocumentLinkRewriter.flattenCommitLinks(
                                "[fix](/files?project=kb&changes=1&rev=abc1234&right=commit)"))
                .isEqualTo("fix (abc1234)");
    }

    @Test
    void otherFilesLinksAreNotCommitLinks() {
        String text =
                "[tree](/files?project=kb) [branch](/files?rev=main)"
                        + " [file](/files?path=a.md&rev=abc1234)";
        assertThat(DocumentLinkRewriter.flattenCommitLinks(text)).isEqualTo(text);
    }
}
