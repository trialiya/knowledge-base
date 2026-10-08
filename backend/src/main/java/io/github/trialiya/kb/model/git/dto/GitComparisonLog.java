package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * История между двумя сравниваемыми ревизиями — то, что {@code git rev-list --left-right --count
 * base...head} называет счётчиками, плюс сами коммиты одной стороны.
 *
 * <p>Перечисляются только коммиты {@code head}: их изменения и есть список файлов сравнения, а
 * коммиты {@code base} к нему отношения не имеют — для них хватает числа.
 *
 * @param ahead сколько коммитов есть в {@code head} и нет в {@code base}
 * @param aheadTruncated счёт {@code ahead} остановился на своём пределе — настоящее число больше
 * @param behind сколько коммитов есть в {@code base} и нет в {@code head}
 * @param behindTruncated то же для {@code behind}: стороны считаются порознь, и точное число
 *     одной не становится нижней границей из-за другой
 * @param commits коммиты {@code head}, которых нет в {@code base}, свежие первыми, — не больше
 *     предела; остальные есть в {@code ahead}
 */
public record GitComparisonLog(
        int ahead, boolean aheadTruncated, int behind, boolean behindTruncated, List<GitCommit> commits) {}
