package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Плашка вызова {@code getLineOrigin} и граница клона у шага. */
class GitLineOriginTest {

    private static final String NEW = "a1b2c3d" + "0".repeat(33);
    private static final String OLD = "e4f5a6b" + "0".repeat(33);
    private static final OffsetDateTime DATE = OffsetDateTime.parse("2024-03-01T10:00:00Z");

    @Test
    void theGistNamesTheQuestionTheOriginAndTheLineBefore() {
        GitLineOrigin origin = new GitLineOrigin(
                "src/A.java",
                12,
                "computeTotal",
                null,
                GitLineOrigin.Status.FOUND,
                List.of(
                        new GitLineOrigin.Step(NEW, "Bob", DATE, "Rename", "src/A.java", 12, "final int x"),
                        new GitLineOrigin.Step(OLD, "Alice", DATE, "Add total", "src/A.java", 10, "int x")),
                new GitLineOrigin.Step(OLD, null, null, null, "src/A.java", 10, "int x = 1"));

        assertThat(origin.getFormattedResponse())
                .startsWith("origin:src/A.java:12 query=computeTotal status=FOUND steps=2")
                .contains("\norigin e4f5a6b Alice 2024-03-01 Add total")
                .endsWith("\nbefore e4f5a6b");
    }

    /** Обход, оборванный пределом, называет последний шаг самым старым, а не ответом. */
    @Test
    void aWalkCutShortNamesItsLastStepTheOldestNotTheOrigin() {
        GitLineOrigin origin = new GitLineOrigin(
                "a.txt",
                1,
                "x",
                null,
                GitLineOrigin.Status.LIMIT,
                List.of(new GitLineOrigin.Step(OLD, "Alice", DATE, "Old", "a.txt", 1, "x")),
                null);

        assertThat(origin.getFormattedResponse()).endsWith("\noldest e4f5a6b Alice 2024-03-01 Old");
    }

    /** На границе клона автор и тема коммита не печатаются: строку он мог и не писать. */
    @Test
    void aBoundaryStepCarriesNoAuthorAndSaysSo() {
        GitLineOrigin.Step step = new GitLineOrigin.Step(OLD, "Alice", DATE, "Squash", "a.txt", 1, "x").atBoundary();
        JsonMapper json = JsonMapper.builder().build();

        assertThat(step.author()).isNull();
        assertThat(step.summary()).isNull();
        assertThat(step.gist()).isEqualTo("e4f5a6b boundary 2024-03-01");
        assertThat(json.writeValueAsString(step)).contains("\"boundary\":true");
        assertThat(json.writeValueAsString(new GitLineOrigin.Step(OLD, null, null, null, "a.txt", 1, "x")))
                .doesNotContain("boundary");
    }
}
