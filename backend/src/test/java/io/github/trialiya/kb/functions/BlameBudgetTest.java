package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Предел ханков в ответе {@code getBlame}: ответ режется по целому ханку, и его {@code toLine}
 * говорит, откуда продолжать.
 */
class BlameBudgetTest {

    @Test
    void anAnswerWithinTheLimitIsLeftAsItIs() {
        GitFileBlame blame = blame(BlameBudget.MAX_HUNKS, null);

        assertThat(BlameBudget.cut(blame)).isFalse();
        assertThat(BlameBudget.cap(blame)).isSameAs(blame);
    }

    /**
     * Ханки по две строки: сотый кончается на строке 200 — там и встаёт {@code toLine}, хотя
     * спрашивали файл целиком; длина файла остаётся длиной файла.
     */
    @Test
    void aLongerAnswerStopsAtTheLastWholeHunkItKeeps() {
        GitFileBlame blame = blame(BlameBudget.MAX_HUNKS + 1, null);

        GitFileBlame capped = BlameBudget.cap(blame);

        assertThat(BlameBudget.cut(blame)).isTrue();
        assertThat(capped.hunks()).hasSize(BlameBudget.MAX_HUNKS);
        assertThat(capped.fromLine()).isEqualTo(1);
        assertThat(capped.toLine()).isEqualTo(2 * BlameBudget.MAX_HUNKS);
        assertThat(capped.lineCount()).isEqualTo(blame.lineCount());
    }

    /** У ответа о диапазоне начало остаётся началом диапазона. */
    @Test
    void aCappedRangeKeepsItsStart() {
        GitFileBlame capped = BlameBudget.cap(blame(BlameBudget.MAX_HUNKS + 5, 41));

        assertThat(capped.fromLine()).isEqualTo(41);
        assertThat(capped.toLine()).isEqualTo(41 + 2 * BlameBudget.MAX_HUNKS - 1);
    }

    /**
     * {@code count} ханков по две строки с начала диапазона; {@code from} — начало спрошенного
     * диапазона, {@code null} — весь файл с первой строки.
     */
    private static GitFileBlame blame(int count, Integer from) {
        int start = from == null ? 1 : from;
        List<GitFileBlame.Hunk> hunks = IntStream.range(0, count)
                .mapToObj(i -> new GitFileBlame.Hunk(start + 2 * i, 2, "a".repeat(40), "A", null, "s", "f", 1))
                .toList();
        return new GitFileBlame(
                "f", null, start + 2 * count - 1, hunks, from, from == null ? null : start + 2 * count - 1);
    }
}
