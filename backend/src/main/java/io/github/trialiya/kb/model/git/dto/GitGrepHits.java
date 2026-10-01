package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Поиск по содержимому для модели ({@code grepContent}): совпадения и признак, что за ними в
 * репозитории могут быть ещё.
 *
 * <p>Размер выдачи этого не говорит. Ровно {@code maxResults} блоков бывает полной выдачей, а
 * прогон с контекстом может упереться в потолок строк вывода задолго до {@code maxResults} — и
 * тогда короткий список значит «не дочитали», а не «больше нет».
 *
 * @param matches блоки в порядке вывода {@code git grep}, не больше запрошенного
 * @param truncated совпадений больше, чем в {@code matches}, или вывод git оборван на потолке
 */
public record GitGrepHits(List<GitGrepMatch> matches, boolean truncated) {}
