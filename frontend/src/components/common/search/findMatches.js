// ─── Поиск по отрендеренному содержимому: чистая часть ──────────────────────
// Ни состояния, ни React: собрать выражение, найти вхождения в поддереве,
// прокрутить к одному из них. Хуки поверх этого — useMatchRanges (пересбор по
// изменениям содержимого), useMatchHighlight (общий реестр подсветки) и
// useFindMatches (то и другое плюс ходьба по совпадениям).

/** Стабильный «ничего не нашлось»: новый литерал на каждый сброс давал бы лишний ре-рендер. */
export const NO_RANGES = [];

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Выражение, которым ищем в тексте, либо null — искать нечего или нечем.
 *
 * Регистр не важен всегда: и `git grep` в этом проекте зовётся с `-i`, и поиск
 * по сообщениям чата на бэке регистронезависим — подсветка обязана находить
 * ровно то же. `regex` — запрос уже является выражением; невалидное для JS (у
 * `git grep -E` синтаксис POSIX ERE, и он шире в одних местах и уже в других)
 * даёт null, а не падение: файл открыт, и отсутствие подсветки — не повод
 * показывать вместо него ошибку.
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

/** Ключ пересбора: по нему видно, что искомое изменилось и Range'и устарели. */
export const matchKey = (query, regex, active = true) =>
  active ? JSON.stringify([!!regex, (query ?? '').trim()]) : '';

/**
 * Range всех вхождений в текстовых узлах root, в порядке документа. Пропускаем
 * поле самого бара (иначе он находил бы собственный счётчик) и редактируемые
 * узлы, поверх которых подсветка всё равно не рисуется. Совпадение, разорванное
 * границей узлов (markdown-форматированием), не находится.
 *
 * `within` сужает область до конкретных блоков: в ленте чата ищут по тексту
 * сообщений, а не по времени отправки и карточкам вызовов инструментов.
 */
export function collectMatchRanges(root, matcher, { within } = {}) {
  const ranges = [];
  if (!matcher) return ranges;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => {
      const el = node.parentElement;
      if (!el || el.closest('[data-find-bar], textarea, script, style')) return NodeFilter.FILTER_REJECT;
      if (within && !el.closest(within)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
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

/**
 * Прокрутить к совпадению. У Range нет scrollIntoView, а ближайший к нему
 * элемент может быть выше экрана целиком (длинный markdown-блок, diff) —
 * поэтому ищем ближайшего прокручиваемого предка внутри области поиска и
 * центрируем совпадение в нём сами. Уже видимое совпадение не двигаем: иначе
 * каждый введённый символ дёргал бы содержимое.
 */
export function scrollRangeIntoView(range, root) {
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
}
