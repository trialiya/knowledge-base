// ─── Общий реестр подсветки совпадений ──────────────────────────────────────
// Подсветка идёт через CSS Custom Highlight API: DOM не трогаем, им владеет
// React (стили — в findBar.css). В браузерах без поддержки её просто нет —
// остаются счётчик и прокрутка к совпадению.

import { useCallback, useEffect, useState } from 'react';
import { NO_RANGES } from './findMatches';

const HL_ALL = 'kb-find';
const HL_ACTIVE = 'kb-find-active';

let nextOwnerId = 0;

// Имена подсветки глобальны для документа, а искать на экране могут сразу
// несколько поверхностей: лента чата, открытый файл, модалка поверх него.
// Поэтому никто не пишет в имена напрямую — каждый объявляет здесь свои Range'и,
// а в имена уходит объединение. Иначе закрытый бар модалки (совпадений ноль)
// стирал бы подсветку под собой, и вернуть её было бы нечем.
const owners = new Map();

const paint = () => {
  const all = [];
  const active = [];
  for (const own of owners.values()) {
    all.push(...own.all);
    active.push(...own.active);
  }
  for (const [name, ranges] of [
    [HL_ALL, all],
    [HL_ACTIVE, active],
  ]) {
    if (ranges.length) window.CSS.highlights.set(name, new window.Highlight(...ranges));
    else window.CSS.highlights.delete(name);
  }
};

const publish = (id, all, active) => {
  if (!window.CSS?.highlights) return;
  if (all.length || active.length) owners.set(id, { all, active });
  else owners.delete(id);
  paint();
};

/**
 * Место в общем реестре подсветки. Возвращает `publish(all, active)`: `active`
 * рисуется контрастнее — это то совпадение (или то сообщение), на котором стоит
 * бар. Кого считать активным, поверхности решают по-разному, поэтому делят
 * Range'и они сами, а реестру отдают уже готовые списки.
 */
export default function useMatchHighlight() {
  const [ownerId] = useState(() => ++nextOwnerId);

  // Уходя, уносим только свои совпадения — чужие в именах остаются.
  useEffect(() => () => publish(ownerId, NO_RANGES, NO_RANGES), [ownerId]);

  return useCallback((all, active) => publish(ownerId, all, active), [ownerId]);
}
