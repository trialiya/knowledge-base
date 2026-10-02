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
        GitFileBlame capped = BlameBudget.cap(blame(BlameBudget.MAX_HUNKS + 5, 1));

        assertThat(capped.fromLine()).isEqualTo(1);
        assertThat(capped.hunks().getLast().fromLine()).isEqualTo(2 * BlameBudget.MAX_HUNKS - 1);
    }

    /** {@code count} ханков по две строки с первой; {@code from} — начало спрошенного диапазона. */
    private static GitFileBlame blame(int count, Integer from) {
        List<GitFileBlame.Hunk> hunks = IntStream.range(0, count)
                .mapToObj(i -> new GitFileBlame.Hunk(1 + 2 * i, 2, "a".repeat(40), "A", null, "s", "f", 1))
                .toList();
        return new GitFileBlame("f", null, 2 * count, hunks, from, from == null ? null : 2 * count);
    }
}
