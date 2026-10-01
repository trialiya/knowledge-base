package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Ответ поиска коммитов для страницы «Поиск» ({@code GET /api/git/commits/grep}).
 *
 * @param commits совпадения, свежие первыми
 * @param truncated за ними могут быть ещё — см. {@link GitCommitSearchResult#truncated()}
 */
public record GitCommitGrepResult(List<GitCommitMatch> commits, boolean truncated) {}
