package io.github.trialiya.kb.model.git.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Группировка плоских совпадений по файлу для страницы поиска. */
class GitGrepResultTest {

    @Test
    void linesOfOneFileGatherUnderItInTheOrderTheyCame() {
        // Не по алфавиту и не по номеру строки: порядок — тот, в каком пришли совпадения.
        List<GitGrepMatch> matches = List.of(
                new GitGrepMatch("b.txt", 2, "needle three"),
                new GitGrepMatch("a.txt", 5, "needle two"),
                new GitGrepMatch("a.txt", 1, "needle one"));

        GitGrepResult result = GitGrepResult.group(matches, false);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
        assertThat(result.files()).extracting(GitGrepResult.File::path).containsExactly("b.txt", "a.txt");
        assertThat(result.files().getLast().lines())
                .extracting(GitGrepResult.Line::line)
                .containsExactly(5, 1);
    }

    /** Обрезана ли выдача, по её размеру не узнать — это слово вызывающего, и оно передаётся как есть. */
    @Test
    void truncatedIsTheCallersWord() {
        List<GitGrepMatch> matches = List.of(new GitGrepMatch("a.txt", 1, "x"), new GitGrepMatch("a.txt", 2, "x"));

        assertThat(GitGrepResult.group(matches, true).truncated()).isTrue();
        assertThat(GitGrepResult.group(matches, false).truncated()).isFalse();
        assertThat(GitGrepResult.group(List.of(), false)).isEqualTo(new GitGrepResult(0, false, List.of()));
    }
}
