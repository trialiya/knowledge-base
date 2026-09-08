// ─── Поиск по отрендеренному содержимому ────────────────────────────────────
// Совпадения подсвечиваются через CSS Custom Highlight API: DOM не трогаем, им
// владеет React (стили — в findBar.css). В браузерах без поддержки остаются
// счётчик и прокрутка к совпадению.

import { useEffect, useEffectEvent, useState } from 'react';

const HL_ALL = 'kb-find';
const HL_ACTIVE = 'kb-find-active';
// Стабильный «нет совпадений»: новый литерал на каждый сброс давал бы лишний ре-рендер.
const NO_MATCHES = [];
// Содержимое меняется само (догрузка превью, diff, переключение markdown) —
// пересобираем совпадения по MutationObserver, склеивая пачку правок одним таймером.
const RECOLLECT_MS = 120;

let nextOwnerId = 0;

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Выражение, которым ищем в тексте, либо null — искать нечего или нечем.
 *
 * Регистр не важен всегда: `git grep` в этом проекте зовётся с `-i`, и
 * подсветка обязана находить ровно то же. `regex` — запрос уже является
 * выражением; невалидное для JS (у `git grep -E` синтаксис POSIX ERE, и он
 * шире в одних местах и уже в других) даёт null, а не падение: файл открыт, и
 * отсутствие подсветки — не повод показывать вместо него ошибку.
 */
export function buildMatcher(query, regex) {
  const q = (query ?? '').trim();
  if (!q) return null;
  try {
    return new RegExp(regex ? q : escapeRegExp(q), 'gi');
  } catch {
    return null;
  }
}

/**
 * Range всех вхождений в текстовых узлах root, в порядке документа. Пропускаем
 * поле самого бара (иначе он находил бы собственный счётчик) и редактируемые
 * узлы, поверх которых подсветка всё равно не рисуется. Совпадение, разорванное
 * границей узлов (markdown-форматированием), не находится — как и в чате.
 */
export function collectMatchRanges(root, matcher) {
  const ranges = [];
  if (!matcher) return ranges;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) =>
      node.parentElement?.closest('[data-find-bar], textarea, script, style')
        ? NodeFilter.FILTER_REJECT
        : NodeFilter.FILTER_ACCEPT,
  });
  let node;
  while ((node = walker.nextNode())) {
    matcher.lastIndex = 0;
    let m;
    while ((m = matcher.exec(node.nodeValue)) !== null) {
      // Выражение вроде `a*` совпадает с пустотой: без сдвига lastIndex цикл
      // не кончится, а пустой Range всё равно нечего подсвечивать.
      if (m[0].length === 0) {
        matcher.lastIndex += 1;
        continue;
      }
      const r = document.createRange();
      r.setStart(node, m.index);
      r.setEnd(node, m.index + m[0].length);
      ranges.push(r);
    }
  }
  return ranges;
}

// Собственная перерисовка бара — не изменение содержимого, по которому ищем.
const isInBar = (node) => {
  const el = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return !!el?.closest?.('[data-find-bar]');
};

// Имена подсветки глобальны для документа, а хуков на экране может быть
// несколько: бар открытого файла и бар модалки, вставшей поверх него. Поэтому
// каждый экземпляр не пишет в имена напрямую, а объявляет здесь свои Range'и —
// в имена уходит объединение. Иначе закрытый бар модалки (совпадений ноль)
// стирал бы подсветку файла под собой, и вернуть её было бы нечем.
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

const publishHighlights = (id, all, active) => {
  if (!window.CSS?.highlights) return;
  if (all.length || active.length) owners.set(id, { all, active });
  else owners.delete(id);
  paint();
};

// У Range нет scrollIntoView, а ближайший к нему элемент может быть выше экрана
// целиком (длинный markdown-блок, diff) — поэтому ищем ближайшего прокручиваемого
// предка внутри области поиска и центрируем совпадение в нём сами. Уже видимое
// совпадение не двигаем: иначе каждый ввод символа дёргал бы содержимое.
const scrollRangeIntoView = (range, root) => {
  const rect = range.getBoundingClientRect();
  if (!rect.height && !rect.width) return;
  for (let el = range.startContainer.parentElement; el && root.contains(el); el = el.parentElement) {
    if (el.scrollHeight <= el.clientHeight + 1) continue;
    if (!/auto|scroll|overlay/.test(getComputedStyle(el).overflowY)) continue;
    const box = el.getBoundingClientRect();
    if (rect.top >= box.top && rect.bottom <= box.bottom) return;
    el.scrollTop += rect.top - box.top - el.clientHeight / 2 + rect.height / 2;
    return;
  }
};

/**
 * Совпадения запроса в поддереве, их подсветка и переход между ними.
 *
 * Запрос и «включён ли поиск» приходят снаружи: у модалки их держит find-бар,
 * у открытого файла — адрес. Хук отвечает только за то, что из этого следует —
 * какие Range нашлись, какой из них активен, куда прокрутить.
 *
 * Активное совпадение считается на чтении, а не хранится: после пересбора (файл
 * догрузился, переключили diff) прежний индекс может выйти за границы, и
 * отдельное состояние пришлось бы чинить эффектом.
 *
 * @param rootRef ref на элемент — область поиска
 * @param query   что искать; пусто — совпадений нет
 * @param regex   трактовать запрос как регулярное выражение
 * @param active  включён ли поиск вообще (закрытый бар совпадений не держит)
 */
export default function useFindMatches({ rootRef, query, regex = false, active = true }) {
  const [matches, setMatches] = useState(NO_MATCHES);
  const [index, setIndex] = useState(0);
  // Кто мы в общем реестре подсветки (см. owners выше). useState, а не ref:
  // писать в ref во время рендера нельзя.
  const [ownerId] = useState(() => ++nextOwnerId);

  // Новый запрос — снова с первого совпадения. Подстройка в рендере, а не в
  // эффекте: это состояние, следующее за пропом (см. frontend-ui.md).
  const key = active ? JSON.stringify([!!regex, (query ?? '').trim()]) : '';
  const [prevKey, setPrevKey] = useState(key);
  if (prevKey !== key) {
    setPrevKey(key);
    setIndex(0);
  }

  const total = matches.length;
  const activeIndex = total ? Math.min(index, total - 1) : -1;

  // Пересбор — useEffectEvent: совпадения живут в DOM, а его до коммита нет, и
  // взять их раньше эффекта неоткуда; при этом пересобрать надо и по смене
  // запроса, и по изменению содержимого, у которых разные зависимости.
  const collect = useEffectEvent(() => {
    const root = rootRef.current;
    const matcher = active ? buildMatcher(query, regex) : null;
    setMatches(root && matcher ? collectMatchRanges(root, matcher) : NO_MATCHES);
  });

  useEffect(() => {
    collect();
  }, [key]);

  // Содержимое поменялось — старые Range указывают на выброшенные узлы.
  useEffect(() => {
    const root = rootRef.current;
    if (!key || !root || typeof MutationObserver === 'undefined') return undefined;
    let timer = null;
    const observer = new MutationObserver((records) => {
      // Перерисовка самого бара (счётчик совпадений) — не повод пересобирать их заново.
      if (records.every((r) => isInBar(r.target))) return;
      clearTimeout(timer);
      timer = setTimeout(collect, RECOLLECT_MS);
    });
    observer.observe(root, { childList: true, subtree: true, characterData: true });
    return () => {
      clearTimeout(timer);
      observer.disconnect();
    };
  }, [rootRef, key]);

  // Подсветка: активное совпадение — отдельным, более контрастным стилем.
  useEffect(() => {
    publishHighlights(
      ownerId,
      matches.filter((_, i) => i !== activeIndex),
      activeIndex >= 0 ? [matches[activeIndex]] : [],
    );
    return () => publishHighlights(ownerId, NO_MATCHES, NO_MATCHES);
  }, [ownerId, matches, activeIndex]);

  useEffect(() => {
    const root = rootRef.current;
    const range = activeIndex >= 0 ? matches[activeIndex] : null;
    if (root && range) scrollRangeIntoView(range, root);
  }, [rootRef, matches, activeIndex]);

  return {
    total,
    activeIndex,
    // Шагаем от ПОКАЗАННОГО совпадения, а не от сырого index: после пересбора,
    // который нашёл меньше (переключили markdown, diff, догрузилось содержимое),
    // они расходятся — и стрелка прыгала бы не с того, что видно на экране.
    // Прижимаем внутри обновления, а не снаружи: два шага в одном батче обязаны
    // дать два шага, а не схлопнуться в один.
    goNext: () => setIndex((i) => (total ? (Math.min(i, total - 1) + 1) % total : 0)),
    goPrev: () => setIndex((i) => (total ? (Math.min(i, total - 1) - 1 + total) % total : 0)),
  };
}
