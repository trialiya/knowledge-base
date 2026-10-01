/**
 * Что сказать под выдачей поиска коммитов, которую бэкенд пометил `truncated`,
 * — ключ перевода (`common:commitSearch.*`) или null, когда выдача полна.
 *
 * Обрезка бывает двух родов, и совет у них разный. Выдача заполнила лимит —
 * совпадений больше, и помогает уточнить запрос. Выдача короче лимита — обход
 * упёрся в свой предел раньше конца истории, и уточнение до более старых
 * коммитов не дотянется. Пустая выдача второго рода — не «нет вовсе», а «нет в
 * просмотренной части».
 *
 * Общая для пикера плейсхолдера и категории «Коммиты» страницы поиска: оба
 * спрашивают один и тот же обход (CommitSearch).
 */
export default function commitSearchNote({ count, limit, truncated }) {
  if (!truncated) return null;
  if (count === 0) return 'common:commitSearch.nothingInSearched';
  return count >= limit ? 'common:commitSearch.refine' : 'common:commitSearch.olderNotSearched';
}
