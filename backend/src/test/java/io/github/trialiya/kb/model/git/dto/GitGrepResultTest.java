package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Группировка плоских совпадений по файлу для страницы поиска. */
class GitGrepResultTest {

    @Test
    void linesOfOneFileGatherUnderItInTheOrderTheyCame() {
        List<GitGrepMatch> matches =
                List.of(
                        new GitGrepMatch("a.txt", 1, "needle one"),
                        new GitGrepMatch("a.txt", 5, "needle two"),
                        new GitGrepMatch("b.txt", 2, "needle three"));

        GitGrepResult result = GitGrepResult.group(matches, 200);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
        assertThat(result.files())
                .extracting(GitGrepResult.File::path)
                .containsExactly("a.txt", "b.txt");
        assertThat(result.files().getFirst().lines())
                .extracting(GitGrepResult.Line::line)
                .containsExactly(1, 5);
    }

    /**
     * Ровно {@code limit} совпадений — git мог остановиться на лимите, выдача считается обрезанной.
     */
    @Test
    void hittingTheLimitMarksTheResultTruncated() {
        List<GitGrepMatch> matches =
                List.of(new GitGrepMatch("a.txt", 1, "x"), new GitGrepMatch("a.txt", 2, "x"));

        assertThat(GitGrepResult.group(matches, 2).truncated()).isTrue();
        assertThat(GitGrepResult.group(List.of(), 2))
                .isEqualTo(new GitGrepResult(0, false, List.of()));
    }
}
