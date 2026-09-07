package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Именованные ревизии репозитория — то, из чего файловый браузер даёт выбрать, какой снимок
 * смотреть.
 *
 * <p>Отдельно от {@link GitBranchStatus}: тот отвечает на «где сейчас стоит рабочее дерево» и
 * поэтому несёт upstream, счётчики и состояние merge. Здесь вопрос другой — «что вообще можно
 * открыть», — и ответ на него не меняется от того, есть ли в рабочем дереве незакоммиченные правки.
 *
 * <p>Хеши коммитов сюда не входят: их не перечисляют, а ищут ({@code GET /commits/search}).
 *
 * @param branches локальные ветки в порядке git (по алфавиту)
 * @param tags теги, свежие первыми — версию обычно ищут последнюю, а не ту, что стоит первой по
 *     алфавиту (где {@code v10} оказывается раньше {@code v9})
 */
public record GitRefs(List<String> branches, List<String> tags) {

    public GitRefs {
        branches = branches == null ? List.of() : List.copyOf(branches);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
