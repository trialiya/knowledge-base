package io.github.trialiya.kb.service.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    /** 20 tokens = 80 chars per chunk, no overlap unless a test asks for it. */
    private static TextChunker chunker(int overlapTokens) {
        return TextChunker.builder().maxTokens(20).overlapTokens(overlapTokens).build();
    }

    @Test
    void shortTextIsOneChunk() {
        assertThat(chunker(0).split("# A\nтело")).containsExactly("# A\nтело");
    }

    @Test
    void headingStaysWithItsBodyAndSectionsDoNotMixAcrossTheBudget() {
        String md = "# Первый\n" + "а".repeat(50) + "\n\n# Второй\n" + "б".repeat(50);

        List<String> chunks = chunker(0).split(md);

        assertThat(chunks).containsExactly("# Первый\n" + "а".repeat(50), "# Второй\n" + "б".repeat(50));
    }

    @Test
    void smallSectionsArePackedTogether() {
        List<String> chunks = chunker(0).split("# A\nx\n\n## B\ny\n\n## C\nz\n\n# D\n" + "w".repeat(70));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).startsWith("# A").contains("## C\nz");
    }

    @Test
    void longSectionRepeatsItsHeadingOnContinuationPieces() {
        String md = "## Длинный\n" + ("слово ".repeat(10).strip() + "\n\n").repeat(4);

        List<String> chunks = chunker(0).split(md);

        assertThat(chunks).hasSizeGreaterThan(1).allMatch(c -> c.startsWith("## Длинный"));
        assertThat(chunks).allMatch(c -> c.length() <= 80);
    }

    @Test
    void fencedBlockWithBlankLinesIsNotCut() {
        String code = "```java\nint a;\n\nint b;\n```";
        String md = "# A\n" + "п".repeat(60) + "\n\n" + code;

        List<String> chunks = chunker(0).split(md);

        assertThat(chunks).anyMatch(c -> c.contains(code));
    }

    @Test
    void headingInsideCodeDoesNotStartASection() {
        String md = "# A\n" + "п".repeat(40) + "\n```sh\n# comment\n```\n" + "т".repeat(40);

        assertThat(chunker(0).split(md)).noneMatch(c -> c.startsWith("# comment"));
    }

    @Test
    void overlapIsNotCarriedAcrossSections() {
        String md = "# A\n" + "а".repeat(60) + "\n\n# B\n" + "б".repeat(60);

        assertThat(chunker(5).split(md)).containsExactly("# A\n" + "а".repeat(60), "# B\n" + "б".repeat(60));
    }

    @Test
    void textWithoutHeadingsStillSplitsByParagraphs() {
        List<String> chunks = chunker(0).split("а".repeat(50) + "\n\n" + "б".repeat(50));

        assertThat(chunks).containsExactly("а".repeat(50), "б".repeat(50));
    }
}
