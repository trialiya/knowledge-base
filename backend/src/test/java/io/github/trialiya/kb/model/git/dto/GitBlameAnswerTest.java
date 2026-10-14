package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.tool.ToolResult;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Плотная форма ответа {@code getBlame}: коммит описан один раз, ханк ссылается на него хешем и
 * опускает путь и строку, совпадающие с очевидными.
 */
class GitBlameAnswerTest {

    private static final String ALICE = "a1b2c3d" + "0".repeat(33);
    private static final String BOB = "b0b0b0b" + "1".repeat(33);
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2024-03-01T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static GitFileBlame.Hunk alice(int from, int count, int source) {
        return new GitFileBlame.Hunk(from, count, ALICE, "Alice", DATE, "Fix parser", "src/A.java", source);
    }

    @Test
    void aCommitIsDescribedOnceHoweverManyHunksItHas() {
        GitFileBlame blame = new GitFileBlame(
                "src/A.java",
                null,
                40,
                List.of(
                        alice(1, 3, 1),
                        new GitFileBlame.Hunk(4, 2, BOB, "Bob", DATE, "Rename", "src/Old.java", 9),
                        alice(6, 1, 4),
                        new GitFileBlame.Hunk(7, 1, null, null, null, null, null, null)),
                null,
                null);

        GitBlameAnswer answer = GitBlameAnswer.of(blame);

        assertThat(answer.commits()).containsOnlyKeys(ALICE, BOB);
        assertThat(answer.commits().get(ALICE))
                .isEqualTo(new GitBlameAnswer.Commit("Alice", DATE, "Fix parser", false));
        assertThat(answer.hunks())
                .containsExactly(
                        // Путь и строка — те же, что у файла и ханка: их нет.
                        new GitBlameAnswer.Hunk(1, 3, ALICE, null, null),
                        new GitBlameAnswer.Hunk(4, 2, BOB, "src/Old.java", 9),
                        new GitBlameAnswer.Hunk(6, 1, ALICE, null, 4),
                        new GitBlameAnswer.Hunk(7, 1, null, null, null));
    }

    /** Форма — та, что уходит модели: через конвертер ответа инструмента, а не голый маппер. */
    @Test
    void theModelsJsonCarriesNoAuthorOnAHunk() {
        GitFileBlame blame = new GitFileBlame("src/A.java", null, 40, List.of(alice(1, 3, 1), alice(4, 1, 2)), 1, 4);

        String text = new CompactToolResultConverter()
                .convert(new ToolResult<>("kb", GitBlameAnswer.of(blame)), ToolResult.class);
        JsonNode json = JSON.readTree(text).get("result");

        assertThat(json.propertyNames()).containsExactly("path", "lineCount", "fromLine", "toLine", "commits", "hunks");
        assertThat(json.at("/commits/" + ALICE + "/author").asString()).isEqualTo("Alice");
        assertThat(json.at("/commits/" + ALICE).has("boundary")).isFalse();
        assertThat(json.at("/hunks/0").propertyNames()).containsExactly("fromLine", "lineCount", "hash");
        assertThat(json.at("/hunks/1/sourceLine").asInt()).isEqualTo(2);
    }

    /** Граница клона — свойство коммита: флаг на нём, автора и темы нет. */
    @Test
    void theBoundaryFlagMovesToTheCommit() {
        GitFileBlame blame =
                new GitFileBlame("src/A.java", null, 3, List.of(alice(1, 3, 1).atBoundary()), null, null);

        GitBlameAnswer.Commit commit = GitBlameAnswer.of(blame).commits().get(ALICE);

        assertThat(commit).isEqualTo(new GitBlameAnswer.Commit(null, DATE, null, true));
        assertThat(JSON.writeValueAsString(commit))
                .contains("\"boundary\":true")
                .doesNotContain("author");
    }

    /** Плашка вызова не меняется с формой ответа: строка на ханк, с автором и границей клона. */
    @Test
    void theGistIsTheBlamesOwn() {
        GitFileBlame blame = new GitFileBlame(
                "src/A.java",
                null,
                40,
                List.of(
                        alice(12, 7, 12).atBoundary(),
                        new GitFileBlame.Hunk(19, 2, BOB, "Bob", DATE, "Rename", "src/Old.java", 9),
                        new GitFileBlame.Hunk(21, 1, null, null, null, null, null, null)),
                12,
                21);

        assertThat(GitBlameAnswer.of(blame).getFormattedResponse()).isEqualTo(blame.getFormattedResponse());
    }

    /** Файл без строк в диапазоне — пустой словарь коммитов в JSON не печатается. */
    @Test
    void anEmptyRangeHasNoCommitsKey() {
        GitFileBlame blame = new GitFileBlame("src/A.java", null, 3, List.of(), 5, 3);

        assertThat(JSON.writeValueAsString(GitBlameAnswer.of(blame))).doesNotContain("commits");
    }
}
