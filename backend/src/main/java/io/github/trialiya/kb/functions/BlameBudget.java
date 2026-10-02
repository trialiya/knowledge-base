package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import java.util.List;

/**
 * Предел ханков в одном ответе {@code getBlame}.
 *
 * <p>Число ханков растёт с историей файла, а не с его длиной: файл в пару тысяч строк, который
 * правили годами, — это сотни ханков, и blame без диапазона вернул бы модели их все. Ответ режется
 * по целому ханку, и его {@code toLine} встаёт на последнюю показанную строку: продолжение — с
 * {@code toLine + 1}, так же, как у {@code getFileContent}, остановленного на пределе.
 *
 * <p>Только для инструмента: колонка blame в UI получает файл целиком.
 */
final class BlameBudget {

    /** Ханков в одном ответе. */
    static final int MAX_HUNKS = 100;

    private BlameBudget() {}

    /** Ответ, укладывающийся в предел, — как есть; иначе его первые {@link #MAX_HUNKS} ханков. */
    static GitFileBlame cap(GitFileBlame blame) {
        List<GitFileBlame.Hunk> hunks = blame.hunks();
        if (hunks.size() <= MAX_HUNKS) {
            return blame;
        }
        List<GitFileBlame.Hunk> kept = hunks.subList(0, MAX_HUNKS);
        GitFileBlame.Hunk last = kept.getLast();
        int from = blame.fromLine() == null ? 1 : blame.fromLine();
        return new GitFileBlame(
                blame.path(),
                blame.commit(),
                blame.lineCount(),
                List.copyOf(kept),
                from,
                last.fromLine() + last.lineCount() - 1);
    }

    static boolean cut(GitFileBlame blame) {
        return blame.hunks().size() > MAX_HUNKS;
    }
}
