/**
 * Лента коммитов по дням — как плашки вызовов в чате идут группами: заголовок
 * дня и коммиты под ним, свежие первыми. Коммиты приходят уже в порядке обхода
 * истории, поэтому группа — это просто подряд идущие коммиты одного дня, а не
 * сортировка: перестановка разошлась бы с тем, что отдал git.
 *
 * День считается в часовом поясе того, кто смотрит, — «вчера» значит его вчера,
 * а не автора коммита.
 */

/** Ключ календарного дня в локальном времени, `null` — для битой даты. */
export function dayKey(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
}

/**
 * @param commits GitCommit[] в порядке обхода
 * @returns `[{ key, date, commits }]` — `date` первого коммита группы, для подписи
 */
export function groupByDay(commits) {
  const groups = [];
  for (const commit of commits) {
    const key = dayKey(commit.date) ?? 'unknown';
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.commits.push(commit);
    else groups.push({ key, date: commit.date, commits: [commit] });
  }
  return groups;
}

/**
 * Подпись дня: «today» / «yesterday» — ключом перевода, иначе дата словами, с
 * годом только у чужого года (иначе он лишний в каждой второй строке).
 *
 * @returns `{ key }` — для перевода, либо `{ text }` — готовая дата
 */
export function dayLabel(value, locale, now = new Date()) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return { text: '—' };
  const key = dayKey(date);
  if (key === dayKey(now)) return { key: 'history.today' };
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (key === dayKey(yesterday)) return { key: 'history.yesterday' };
  const options = { day: 'numeric', month: 'long' };
  if (date.getFullYear() !== now.getFullYear()) options.year = 'numeric';
  return { text: date.toLocaleDateString(locale, options) };
}

/** Время коммита без даты — дата уже в заголовке группы. */
export function timeOf(value, locale) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}
