package io.github.trialiya.kb.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.utils.MarkdownSections.Section;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MarkdownSectionsTest {

    private static Section section(String markdown, String path) {
        return MarkdownSections.parse(markdown).stream()
                .filter(s -> s.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("section not found: " + path));
    }

    private static String contentOf(String markdown, String path) {
        Section s = section(markdown, path);
        return markdown.substring(s.startOffset(), s.endOffset());
    }

    @Nested
    class Parse {

        @Test
        void splitsFlatHeadingsIntoSections() {
            String md = "# A\ntext a\n# B\ntext b\n";

            List<Section> sections = MarkdownSections.parse(md);

            assertThat(sections).extracting(Section::path).containsExactly("A", "B");
            assertThat(contentOf(md, "A")).isEqualTo("# A\ntext a\n");
            assertThat(contentOf(md, "B")).isEqualTo("# B\ntext b\n");
        }

        @Test
        void sectionSpansItsWholeSubtree() {
            String md = "# A\n## A1\n## A2\n### A2a\n# B\n";

            Section a = section(md, "A");
            assertThat(md.substring(a.startOffset(), a.endOffset()))
                    .isEqualTo("# A\n## A1\n## A2\n### A2a\n");
            assertThat(a.subsections()).isEqualTo(2); // A1, A2 (A2a is nested deeper)
        }

        @Test
        void buildsNestedPaths() {
            String md = "# Гайд\n## Установка\n### Docker\nтекст\n## FAQ\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly(
                            "Гайд", "Гайд > Установка", "Гайд > Установка > Docker", "Гайд > FAQ");
        }

        @Test
        void skippedLevelStillNestsUnderNearestShallowerHeading() {
            String md = "# A\n### deep\n## B\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly("A", "A > deep", "A > B");
        }

        @Test
        void duplicatePathsGetOccurrenceSuffix() {
            String md = "# FAQ\n## Вопрос\nодин\n## Вопрос\nдва\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly("FAQ", "FAQ > Вопрос", "FAQ > Вопрос[2]");
            assertThat(contentOf(md, "FAQ > Вопрос[2]")).isEqualTo("## Вопрос\nдва\n");
        }

        @Test
        void ignoresHeadingsInsideFencedCodeBlocks() {
            String md = "# A\n```bash\n# not a heading\n```\n~~~\n## also not\n~~~\n# B\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly("A", "B");
        }

        @Test
        void fenceClosesOnlyWithMatchingMarker() {
            // ``` inside a ~~~ fence does not close it; the hidden heading stays ignored and
            // the whole fenced block lands in the preamble.
            String md = "~~~\n```\n# hidden\n~~~\n# B\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly(MarkdownSections.PREAMBLE_PATH, "B");
        }

        @Test
        void textBeforeFirstHeadingBecomesPreamble() {
            String md = "intro line\n\n# A\ntext\n";

            List<Section> sections = MarkdownSections.parse(md);

            assertThat(sections)
                    .extracting(Section::path)
                    .containsExactly(MarkdownSections.PREAMBLE_PATH, "A");
            assertThat(contentOf(md, MarkdownSections.PREAMBLE_PATH)).isEqualTo("intro line\n\n");
            assertThat(sections.get(0).level()).isZero();
        }

        @Test
        void documentWithoutHeadingsIsOnePreamble() {
            String md = "just text\nno headings\n";

            List<Section> sections = MarkdownSections.parse(md);

            assertThat(sections)
                    .extracting(Section::path)
                    .containsExactly(MarkdownSections.PREAMBLE_PATH);
            assertThat(sections.get(0).chars()).isEqualTo(md.length());
        }

        @Test
        void blankAndEmptyTextsYieldNoSections() {
            assertThat(MarkdownSections.parse("")).isEmpty();
            assertThat(MarkdownSections.parse("  \n\n")).isEmpty();
        }

        @Test
        void hashWithoutSpaceIsNotAHeading() {
            String md = "# A\n#hashtag\n# B\n";

            assertThat(MarkdownSections.parse(md))
                    .extracting(Section::path)
                    .containsExactly("A", "B");
        }

        @Test
        void trailingClosingHashesAreStrippedFromTitle() {
            String md = "## Title ###\ntext\n";

            assertThat(MarkdownSections.parse(md).get(0).title()).isEqualTo("Title");
        }

        @Test
        void lastSectionRunsToEndOfTextWithoutTrailingNewline() {
            String md = "# A\ntext\n# B\nlast line";

            assertThat(contentOf(md, "B")).isEqualTo("# B\nlast line");
        }
    }

    @Nested
    class Replace {

        @Test
        void replacesMiddleSectionKeepingNeighbours() {
            String md = "# A\ntext a\n# B\nold b\n# C\ntext c\n";

            String result = MarkdownSections.replaceSection(md, section(md, "B"), "# B\nnew b");

            assertThat(result).isEqualTo("# A\ntext a\n# B\nnew b\n\n# C\ntext c\n");
        }

        @Test
        void replacesSubtreeIncludingSubsections() {
            String md = "# A\n## A1\nold\n## A2\nold\n# B\n";

            String result =
                    MarkdownSections.replaceSection(md, section(md, "A"), "# A\n## A1\nnew");

            assertThat(result).isEqualTo("# A\n## A1\nnew\n\n# B\n");
        }

        @Test
        void replacesLastSectionWithSingleTrailingNewline() {
            String md = "# A\ntext\n# B\nold\n";

            String result = MarkdownSections.replaceSection(md, section(md, "B"), "# B\nnew");

            assertThat(result).isEqualTo("# A\ntext\n# B\nnew\n");
        }

        @Test
        void replacesPreamble() {
            String md = "old intro\n\n# A\ntext\n";

            String result =
                    MarkdownSections.replaceSection(
                            md, section(md, MarkdownSections.PREAMBLE_PATH), "new intro");

            assertThat(result).isEqualTo("new intro\n\n# A\ntext\n");
        }

        @Test
        void reparsingAfterReplaceKeepsOtherSectionsIntact() {
            String md = "# A\ntext a\n## A1\nsub\n# B\ntext b\n";

            String result =
                    MarkdownSections.replaceSection(md, section(md, "A > A1"), "## A1\nизменено");

            assertThat(contentOf(result, "A > A1")).isEqualTo("## A1\nизменено\n\n");
            assertThat(contentOf(result, "B")).isEqualTo("# B\ntext b\n");
        }

        @Test
        void emptyContentDeletesTheSection() {
            String md = "# A\ntext a\n# B\nold b\n# C\ntext c\n";

            String result = MarkdownSections.replaceSection(md, section(md, "B"), "");

            assertThat(result).isEqualTo("# A\ntext a\n# C\ntext c\n");
        }
    }

    @Nested
    class Insert {

        @Test
        void insertsBeforeAnchorSection() {
            String md = "# A\ntext a\n# B\ntext b\n";

            String result =
                    MarkdownSections.insertSection(md, section(md, "B"), "# New\nтекст", true);

            assertThat(result).isEqualTo("# A\ntext a\n\n# New\nтекст\n\n# B\ntext b\n");
        }

        @Test
        void insertsAfterAnchorSubtree() {
            String md = "# A\n## A1\nsub\n# B\ntext b\n";

            String result =
                    MarkdownSections.insertSection(md, section(md, "A"), "# New\nтекст", false);

            assertThat(result).isEqualTo("# A\n## A1\nsub\n\n# New\nтекст\n\n# B\ntext b\n");
        }

        @Test
        void insertsAfterLastSectionWithSingleTrailingNewline() {
            String md = "# A\ntext\n# B\nlast";

            String result =
                    MarkdownSections.insertSection(md, section(md, "B"), "# C\nновая", false);

            assertThat(result).isEqualTo("# A\ntext\n# B\nlast\n\n# C\nновая\n");
        }

        @Test
        void insertsBeforeFirstSectionAtDocumentStart() {
            String md = "# A\ntext\n";

            String result =
                    MarkdownSections.insertSection(
                            md, section(md, "A"), "# Intro\nвступление", true);

            assertThat(result).isEqualTo("# Intro\nвступление\n\n# A\ntext\n");
        }

        @Test
        void doesNotDoubleBlankLinesAroundInsertion() {
            String md = "# A\ntext a\n\n# B\ntext b\n";

            String result =
                    MarkdownSections.insertSection(md, section(md, "B"), "# New\nтекст", true);

            assertThat(result).isEqualTo("# A\ntext a\n\n# New\nтекст\n\n# B\ntext b\n");
        }
    }

    @Nested
    class Rename {

        @Test
        void renamesHeadingKeepingLevelAndBody() {
            String md = "# A\ntext a\n## Старое\nтело\n# B\n";

            String result = MarkdownSections.renameHeading(md, section(md, "A > Старое"), "Новое");

            assertThat(result).isEqualTo("# A\ntext a\n## Новое\nтело\n# B\n");
        }

        @Test
        void renamesHeadingOnTheLastLineWithoutNewline() {
            String md = "# A\ntext\n## Старое";

            String result = MarkdownSections.renameHeading(md, section(md, "A > Старое"), "Новое");

            assertThat(result).isEqualTo("# A\ntext\n## Новое");
        }

        @Test
        void stripsTrailingClosingHashesOfTheOldHeading() {
            String md = "## Старое ###\nтело\n";

            String result = MarkdownSections.renameHeading(md, section(md, "Старое"), "Новое");

            assertThat(result).isEqualTo("## Новое\nтело\n");
        }
    }
}
