package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Поиск по сообщениям коммитов для страницы поиска ({@code GET /api/git/commits/grep}).
 *
 * <p>У {@code git log} нет индекса по сообщениям, и обход истории ограничен, поэтому «ничего не
 * найдено» бывает двух видов: история кончилась — или кончился предел обхода, а дальше по истории
 * совпадения могли быть. Второе и есть {@code truncated}; без него пустая выдача на длинной истории
 * утверждала бы, что искомого нет.
 *
 * @param commits совпавшие коммиты, свежие первыми, с описанием в {@code body}
 * @param truncated история просмотрена не вся: выдача упёрлась в лимит запроса или обход — в свой
 *     предел
 */
public record GitCommitSearchResult(List<GitCommit> commits, boolean truncated) {}
