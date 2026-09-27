package io.github.trialiya.kb.service.file.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownOutlineParserTest {

    private final MarkdownOutlineParser parser = new MarkdownOutlineParser();

    @Test
    void headingsBecomeSymbolsSpanningTheirSubtree() {
        String source =
                String.join(
                        "\n",
                        "Intro text", // 1
                        "", // 2
                        "# Title", // 3
                        "body", // 4
                        "## Install", // 5
                        "```", // 6
                        "# not a heading", // 7
                        "```", // 8
                        "## Usage", // 9
                        "text", // 10
                        "# Appendix", // 11
                        "end", // 12
                        "");

        List<GitSymbol> symbols = parser.parse("markdown", source);

        assertEquals(
                List.of(
                        new GitSymbol("preamble", "_preamble", "_preamble", 1, 2),
                        new GitSymbol("h1", "Title", "Title", 3, 10),
                        new GitSymbol("h2", "Install", "Title > Install", 5, 8),
                        new GitSymbol("h2", "Usage", "Title > Usage", 9, 10),
                        new GitSymbol("h1", "Appendix", "Appendix", 11, 12)),
                symbols);
    }

    @Test
    void lastSectionWithoutTrailingNewlineEndsOnLastLine() {
        List<GitSymbol> symbols = parser.parse("markdown", "# A\ntext\n## B");

        assertEquals(new GitSymbol("h1", "A", "A", 1, 3), symbols.get(0));
        assertEquals(new GitSymbol("h2", "B", "A > B", 3, 3), symbols.get(1));
    }

    @Test
    void emptyFileHasNoSymbols() {
        assertTrue(parser.parse("markdown", "").isEmpty());
    }
}
