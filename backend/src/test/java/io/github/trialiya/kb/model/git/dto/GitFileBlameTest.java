package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Плашка вызова {@code getBlame}: заголовок и первые ханки строкой каждый. */
class GitFileBlameTest {

    private static final String HASH = "a1b2c3d" + "0".repeat(33);

    @Test
    void theGistNamesTheRangeAndOneLinePerHunk() {
        GitFileBlame blame = new GitFileBlame(
                "src/A.java",
                null,
                40,
                List.of(
                        new GitFileBlame.Hunk(
                                12,
                                7,
                                HASH,
                                "Alice",
                                OffsetDateTime.parse("2024-03-01T10:00:00+03:00"),
                                "Fix parser",
                                "src/A.java",
                                12),
                        new GitFileBlame.Hunk(19, 1, null, null, null, null, null, null)),
                12,
                19);

        assertThat(blame.getFormattedResponse())
                .contains("blame:src/A.java")
                .contains("range=12-19")
                .contains("\n12-18 a1b2c3d Alice 2024-03-01 Fix parser")
                .endsWith("\n19 uncommitted");
    }

    /** Ханк с хешем, но без описания коммита (парсер оставил его голым), — без «null» в плашке. */
    @Test
    void aHunkWithoutItsCommitsFieldsPrintsNoNulls() {
        GitFileBlame blame = new GitFileBlame(
                "a.txt", null, 1, List.of(new GitFileBlame.Hunk(1, 1, HASH, null, null, null, null, 1)), null, null);

        assertThat(blame.getFormattedResponse()).endsWith("\n1 a1b2c3d").doesNotContain("null");
    }

    @Test
    void aBoundaryHunkIsMarkedInTheGistWithoutTheCommitsAuthor() {
        GitFileBlame blame = new GitFileBlame(
                "a.txt",
                null,
                3,
                List.of(new GitFileBlame.Hunk(
                                1, 3, HASH, "Alice", OffsetDateTime.parse("2024-03-01T10:00:00Z"), "Squash", "a.txt", 1)
                        .atBoundary()),
                null,
                null);

        assertThat(blame.getFormattedResponse()).endsWith("\n1-3 a1b2c3d boundary 2024-03-01");
    }

    /** Флаг печатается только там, где он есть: у обычного ханка ключа {@code boundary} нет. */
    @Test
    void onlyABoundaryHunkCarriesTheFlagInJson() {
        JsonMapper json = JsonMapper.builder().build();
        GitFileBlame.Hunk plain = new GitFileBlame.Hunk(1, 1, HASH, null, null, null, null, 1);

        assertThat(json.writeValueAsString(plain)).doesNotContain("boundary");
        assertThat(json.writeValueAsString(plain.atBoundary())).contains("\"boundary\":true");
    }
}
