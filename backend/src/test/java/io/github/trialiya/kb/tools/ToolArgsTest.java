package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gap-filling policy itself: which shapes of "the model left it out" become a default and which
 * become an error the model reads back. The distinction that matters most here is {@code
 * requireText} vs {@code requireContent} — for a name, blank is missing; for a payload, an explicit
 * empty string is a real instruction and only {@code null} is a gap.
 */
class ToolArgsTest {

    @Test
    void aMissingNameIsAnErrorThatSaysWhichArgument() {
        assertThatThrownBy(() -> ToolArgs.requireText(null, "filePath"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'filePath'")
                .hasMessageContaining("Call the tool again");
    }

    @Test
    void blankIsMissingForANameButNotForContent() {
        assertThatThrownBy(() -> ToolArgs.requireText("   ", "query"))
                .isInstanceOf(IllegalArgumentException.class);
        // A deliberate empty body — create an empty file, delete a fragment — is a real value.
        assertThat(ToolArgs.requireContent("", "content")).isEmpty();
        assertThat(ToolArgs.requireContent("   ", "content")).isEqualTo("   ");
        assertThatThrownBy(() -> ToolArgs.requireContent(null, "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'content'");
    }

    @Test
    void anIdIsAcceptedHoweverTheModelSpelledIt() {
        assertThat(ToolArgs.requireId(42L, "documentId")).isEqualTo(42L);
        assertThat(ToolArgs.requireId(42, "documentId")).isEqualTo(42L);
        assertThat(ToolArgs.requireId("42", "documentId")).isEqualTo(42L);
        assertThat(ToolArgs.requireId(" 42 ", "documentId")).isEqualTo(42L);
    }

    @Test
    void aNonNumericIdFailsNamingTheArgumentAndQuotingWhatCameIn() {
        assertThatThrownBy(() -> ToolArgs.requireId("12,13", "documentId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'documentId'")
                .hasMessageContaining("12,13");
        assertThatThrownBy(() -> ToolArgs.requireId("", "documentId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is missing");
    }

    @Test
    void anEmptyListIsTheSameGapAsAnAbsentOne() {
        assertThatThrownBy(() -> ToolArgs.requireNonEmpty(List.of(), "positions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'positions'");
        assertThatThrownBy(() -> ToolArgs.requireNonEmpty(null, "positions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ToolArgs.requireNonEmpty(List.of(1L), "positions")).containsExactly(1L);
    }

    @Test
    void optionalsFallBackWithoutComplaining() {
        assertThat(ToolArgs.orDefault(null, "hybrid")).isEqualTo("hybrid");
        assertThat(ToolArgs.orDefault("  ", "hybrid")).isEqualTo("hybrid");
        assertThat(ToolArgs.orDefault("keyword", "hybrid")).isEqualTo("keyword");
        assertThat(ToolArgs.orDefault((Boolean) null, true)).isTrue();
        assertThat(ToolArgs.orDefault(Boolean.FALSE, true)).isFalse();
    }

    @Test
    void zeroIsARealAnswerForACountAndAnUnsetOneForALimit() {
        // contextLines=0 means "the matching line only" and must survive.
        assertThat(ToolArgs.orDefault(0, 1)).isZero();
        // maxResults=0 asks for nothing back, which is never what the caller meant.
        assertThat(ToolArgs.positiveOrDefault(0, 50)).isEqualTo(50);
        assertThat(ToolArgs.positiveOrDefault(-3, 50)).isEqualTo(50);
        assertThat(ToolArgs.positiveOrDefault(null, 50)).isEqualTo(50);
        assertThat(ToolArgs.positiveOrDefault(5, 50)).isEqualTo(5);
    }
}
