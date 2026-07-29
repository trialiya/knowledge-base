// ─── Выбор записей сравнения ─────────────────────────────────────────────────
// Чистая логика галочек в списке различий. Отдельно от компонента, потому что
// правила тут не про вёрстку, а про то, что бэк вообще сможет применить.

/** Статусы, для которых импорт что-то делает. unchanged выбирать нечего. */
const ACTIONABLE = new Set(['added', 'modified', 'missing']);

export const isActionable = (entry) => ACTIONABLE.has(entry.status);

/** 'a/b/c' → ['a', 'a/b'] */
export const ancestorsOf = (path) => {
  const parts = path.split('/');
  return parts.slice(0, -1).map((_, i) => parts.slice(0, i + 1).join('/'));
};

const isDescendant = (path, ancestor) => path.startsWith(`${ancestor}/`);

/**
 * Переключает одну запись, поддерживая согласованность выбора.
 *
 * Включение тянет за собой предков со статусом added: без созданной папки
 * ребёнку не к чему прицепиться, и бэк такую ветку просто пропустит. Выключение
 * папки снимает её потомков по той же причине — оставленная галочка на ребёнке
 * ничего бы не сделала, но выглядела бы как обещание.
 *
 * @param {object[]} entries все записи сравнения
 * @param {Set<string>} selected текущий выбор
 * @param {string} path путь переключаемой записи
 * @returns {Set<string>} новый выбор
 */
export const toggleEntry = (entries, selected, path) => {
  const next = new Set(selected);
  if (next.has(path)) {
    next.delete(path);
    entries.forEach((e) => {
      if (isDescendant(e.path, path)) next.delete(e.path);
    });
    return next;
  }
  next.add(path);
  const byPath = new Map(entries.map((e) => [e.path, e]));
  ancestorsOf(path).forEach((ancestor) => {
    if (byPath.get(ancestor)?.status === 'added') next.add(ancestor);
  });
  return next;
};

/** Все записи, с которыми импорту есть что делать. */
export const selectAllActionable = (entries) => new Set(entries.filter(isActionable).map((e) => e.path));

/** Сводка выбора: сколько чего применится. */
export const summarizeSelection = (entries, selected) => {
  const counts = { added: 0, modified: 0, missing: 0 };
  entries.forEach((e) => {
    if (selected.has(e.path) && e.status in counts) counts[e.status] += 1;
  });
  return counts;
};
