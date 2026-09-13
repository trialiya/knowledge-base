// ─── Общий реестр подсветки ─────────────────────────────────────────────────
// Подсветка идёт через CSS Custom Highlight API: DOM не трогаем, им владеет
// React (стили — там же, где стили самой поверхности). В браузерах без
// поддержки её просто нет — у find-бара остаются счётчик и прокрутка к
// совпадению, у композера — строка-подсказка над полем.

import { useCallback, useEffect, useState } from 'react';

const HL_ALL = 'kb-find';
const HL_ACTIVE = 'kb-find-active';

let nextOwnerId = 0;

// Имена подсветки глобальны для документа, а рисовать по ним могут сразу
// несколько поверхностей: лента чата, открытый файл, модалка поверх него.
// Поэтому никто не пишет в имена напрямую — каждый объявляет здесь свои Range'и,
// а в имена уходит объединение. Иначе закрытый бар модалки (совпадений ноль)
// стирал бы подсветку под собой, и вернуть её было бы нечем.
const owners = new Map();

// Имена, лежащие в CSS.highlights сейчас: по ним видно, какое из них осталось
// без Range'ей и подлежит удалению. Перебрать сами highlights нельзя — там
// бывают и чужие имена (расширения браузера, будущий код), а стирать мы вправе
// только своё.
let painted = new Set();

const paint = () => {
  const merged = new Map();
  for (const own of owners.values()) {
    for (const [name, ranges] of Object.entries(own)) {
      const acc = merged.get(name);
      if (acc) acc.push(...ranges);
      else merged.set(name, [...ranges]);
    }
  }
  for (const name of painted) if (!merged.has(name)) window.CSS.highlights.delete(name);
  for (const [name, ranges] of merged) window.CSS.highlights.set(name, new window.Highlight(...ranges));
  painted = new Set(merged.keys());
};

const publish = (id, byName) => {
  if (!window.CSS?.highlights) return;
  const kept = Object.entries(byName).filter(([, ranges]) => ranges.length);
  if (kept.length) owners.set(id, Object.fromEntries(kept));
  else owners.delete(id);
  paint();
};

/**
 * Место в общем реестре: возвращает `publish({ [имя подсветки]: Range[] })`.
 * Имя — своё у каждой поверхности (`kb-find`, `kb-composer-command`), и рядом
 * с ним в CSS живёт правило `::highlight(имя)`.
 */
export function useHighlightOwner() {
  const [ownerId] = useState(() => ++nextOwnerId);

  // Уходя, уносим только свои Range'и — чужие в именах остаются.
  useEffect(() => () => publish(ownerId, {}), [ownerId]);

  return useCallback((byName) => publish(ownerId, byName), [ownerId]);
}

/**
 * Место в реестре для find-бара. Возвращает `publish(all, active)`: `active`
 * рисуется контрастнее — это то совпадение (или то сообщение), на котором стоит
 * бар. Кого считать активным, поверхности решают по-разному, поэтому делят
 * Range'и они сами, а реестру отдают уже готовые списки.
 */
export default function useMatchHighlight() {
  const publishNamed = useHighlightOwner();

  return useCallback((all, active) => publishNamed({ [HL_ALL]: all, [HL_ACTIVE]: active }), [publishNamed]);
}
