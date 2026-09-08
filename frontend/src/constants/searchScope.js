/**
 * Категории единого поиска (`/search`, в адресе — `?in=<key>`).
 *
 * Категория ровно одна: пользователь ищет либо по файлам репозитория, либо по
 * документам базы знаний, либо по чатам — у каждой свой эндпоинт, свои фильтры
 * и своя форма результата, и смешивать их в одном списке нечем. Счётчики
 * остальных категорий раздел показывает рядом, поэтому запрашивает все три.
 */
export const SEARCH_SCOPE = {
  FILES: 'files',
  DOCS: 'docs',
  CHATS: 'chats',
};

/** Порядок категорий в левой панели. */
export const SEARCH_SCOPES = [SEARCH_SCOPE.FILES, SEARCH_SCOPE.DOCS, SEARCH_SCOPE.CHATS];

/**
 * Категория по умолчанию для поиска, запущенного из раздела `view`: человек
 * ищет то, на что смотрит. Разделы без своего вида результатов (админка,
 * настройки) попадают в файлы — как и прямой заход по `/search` без `?in=`.
 */
export function scopeForView(view) {
  if (view === 'knowledge') return SEARCH_SCOPE.DOCS;
  if (view === 'chat') return SEARCH_SCOPE.CHATS;
  return SEARCH_SCOPE.FILES;
}

/** Известная категория или дефолт — адрес мог принести что угодно. */
export function normalizeScope(scope) {
  return SEARCH_SCOPES.includes(scope) ? scope : SEARCH_SCOPE.FILES;
}
