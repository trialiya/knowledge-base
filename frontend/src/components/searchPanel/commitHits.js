/**
 * Ответ поиска коммитов (`gitApi.grepCommits`) в форме категории: карточка на
 * коммит, внутри — строки описания, где встретился запрос.
 *
 * Где совпало, говорит бэкенд — строки описания (`lines`), заголовок
 * (`subjectMatch`), один только префикс хеша (`hashMatch`), — как он говорит
 * это и файлам, и документам. Здесь — только форма карточки и счёт: строка
 * описания — одно совпадение, заголовок — ещё одно, коммит без того и другого
 * (по хешу) — одно.
 *
 * `truncated` приходит от бэкенда как есть: история просмотрена не вся, и
 * дальше по ней могли быть ещё совпадения — даже при пустой выдаче.
 *
 * @param result ответ gitApi.grepCommits — { commits: [{ commit, subjectMatch, hashMatch, lines }], truncated }
 * @param limit  сколько коммитов просили — подписи обрезки (commitSearchNote) он нужен
 */
export default function commitHits({ commits, truncated }, limit) {
  const found = commits.map(({ commit, subjectMatch, hashMatch, lines }) => ({
    ...commit,
    subjectMatch,
    hashMatch,
    lines,
  }));
  const total = found.reduce((sum, c) => sum + Math.max(1, c.lines.length + (c.subjectMatch ? 1 : 0)), 0);
  return { total, truncated, limit, commits: found };
}
