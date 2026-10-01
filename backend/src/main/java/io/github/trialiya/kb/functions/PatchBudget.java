package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.util.List;

/**
 * Предел патчей на один ответ {@code getCommitDiff} / {@code getUncommittedChanges}.
 *
 * <p>Каждый патч уже обрезан до 500 строк ({@code Diffs.MAX_DIFF_LINES}), но число файлов в ответе
 * ничем не ограничено: рабочее дерево после массовой правки или пачка коммитов через запятую с
 * {@code includePatch} вернули бы модели десятки тысяч строк. Здесь патчи идут в ответ по порядку,
 * пока их сумма не дойдёт до {@link #MAX_LINES}; у файла, чей патч уже не влез, остаются статус и
 * счётчики, а вместо патча — строка о том, как попросить его отдельно. Меньший патч дальше по
 * списку всё ещё может влезть: бюджет не закрывается на первом отказе.
 *
 * <p>Только для инструментов: REST «Файлов» показывает патч одного выбранного файла и этот предел
 * не проходит.
 */
final class PatchBudget {

    /** Сумма строк всех патчей одного ответа. */
    static final int MAX_LINES = 3000;

    static final String LEFT_OUT = "... (patch left out: this answer's patches already reach "
            + MAX_LINES
            + " lines — ask for this file alone)";

    private int left = MAX_LINES;

    static List<GitDiffEntry> ofEntries(List<GitDiffEntry> entries) {
        PatchBudget budget = new PatchBudget();
        return entries.stream().map(budget::take).toList();
    }

    static List<GitCommit> ofCommits(List<GitCommit> commits) {
        PatchBudget budget = new PatchBudget();
        return commits.stream()
                .map(c -> c.files() == null
                        ? c
                        : new GitCommit(
                                c.hash(),
                                c.shortHash(),
                                c.author(),
                                c.email(),
                                c.date(),
                                c.message(),
                                c.body(),
                                c.files().stream().map(budget::take).toList(),
                                c.parents()))
                .toList();
    }

    private GitDiffEntry take(GitDiffEntry entry) {
        String patch = entry.patch();
        if (patch == null) {
            return entry;
        }
        long lines = patch.lines().count();
        if (lines <= left) {
            left -= (int) lines;
            return entry;
        }
        return new GitDiffEntry(
                entry.status(), entry.path(), entry.oldPath(), entry.additions(), entry.deletions(), null, LEFT_OUT);
    }
}
