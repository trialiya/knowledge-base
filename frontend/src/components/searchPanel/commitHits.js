const norm = (s) => s.toLowerCase();

/**
 * Ответ поиска коммитов (`GitCommit[]` с телами) в форме категории: карточка на
 * коммит, внутри — строки описания, где встретился запрос.
 *
 * Бэкенд говорит только «коммит подошёл», а где именно — нет, поэтому место
 * находится здесь тем же сравнением без учёта регистра, каким искал он.
 * Совпавший заголовок стоит в шапке карточки, совпавшие строки тела — под ней.
 * Коммит, найденный только по префиксу хеша, остаётся без строк и помечен
 * `hashMatch`: иначе он выглядел бы найденным непонятно за что.
 *
 * Счёт совпадений — как у файлов: строка описания — одно, заголовок — ещё
 * одно, коммит без того и другого (по хешу) — одно.
 *
 * `truncated` приходит от бэкенда как есть: история просмотрена не вся, и
 * дальше по ней могли быть ещё совпадения — даже при пустой выдаче.
 *
 * @param result ответ gitApi.grepCommits — { commits, truncated }
 * @param query  строка запроса
 */
export default function commitHits({ commits, truncated }, query) {
  const q = norm(query.trim());
  const found = commits.map((commit) => {
    const subjectMatch = norm(commit.message).includes(q);
    const lines = (commit.body || '')
      .split('\n')
      .map((text, i) => ({ line: i + 1, text }))
      .filter(({ text }) => norm(text).includes(q));
    const hashMatch = !subjectMatch && lines.length === 0 && commit.hash.startsWith(q);
    return { ...commit, subjectMatch, hashMatch, lines };
  });
  const total = found.reduce((sum, c) => sum + Math.max(1, c.lines.length + (c.subjectMatch ? 1 : 0)), 0);
  return { total, truncated, commits: found };
}
