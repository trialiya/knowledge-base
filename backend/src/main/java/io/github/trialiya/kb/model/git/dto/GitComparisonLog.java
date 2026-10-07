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
 * @param behind сколько коммитов есть в {@code base} и нет в {@code head}
 * @param countsTruncated счёт остановился на своём пределе — настоящие числа больше
 * @param commits коммиты {@code head}, которых нет в {@code base}, свежие первыми, — не больше
 *     предела; остальные есть в {@code ahead}
 */
public record GitComparisonLog(int ahead, int behind, boolean countsTruncated, List<GitCommit> commits) {}
