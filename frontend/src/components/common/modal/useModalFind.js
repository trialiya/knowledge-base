// ─── In-modal search (Ctrl+F) ───────────────────────────────────────────────
// Пока открыта модалка, Ctrl+F ищет ТОЛЬКО по её содержимому: браузерный поиск
// прошёлся бы по всей странице, включая скрытый под оверлеем интерфейс, до
// которого пользователю сейчас нет дела. Совпадения подсвечиваются через CSS
// Custom Highlight API — DOM не трогаем, им владеет React (см. modalFind.css);
// в браузерах без поддержки остаются счётчик и прокрутка к совпадению.

import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react';

const HL_ALL = 'kb-modal-find';
const HL_ACTIVE = 'kb-modal-find-active';
// Стабильный «нет совпадений»: новый литерал на каждый сброс давал бы лишний ре-рендер.
const NO_MATCHES = [];
// Содержимое модалки меняется само (догрузка превью, diff, история версий) —
// пересобираем совпадения по MutationObserver, склеивая пачку правок одним таймером.
const RECOLLECT_MS = 120;

const inFindBar = (node) => {
  const el = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return !!el?.closest?.('[data-modal-find-bar]');
};

/**
 * Range всех вхождений query (без учёта регистра) в текстовых узлах root, в
 * порядке документа. Пропускаем поле самого find-бара (иначе он находил бы
 * собственный счётчик) и редактируемые/служебные узлы, поверх которых подсветка
 * всё равно не рисуется. Совпадение, разорванное границей узлов (markdown-
 * форматированием, подсветкой синтаксиса), не находится — как и в чате.
 */
export function collectMatchRanges(root, query) {
  const q = query.toLowerCase();
  const ranges = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) =>
      node.parentElement?.closest('[data-modal-find-bar], textarea, script, style')
        ? NodeFilter.FILTER_REJECT
        : NodeFilter.FILTER_ACCEPT,
  });
  let node;
  while ((node = walker.nextNode())) {
    const lower = node.nodeValue.toLowerCase();
    let i = lower.indexOf(q);
    while (i !== -1) {
      const r = document.createRange();
      r.setStart(node, i);
      r.setEnd(node, i + q.length);
      ranges.push(r);
      i = lower.indexOf(q, i + q.length);
    }
  }
  return ranges;
}

const setHighlight = (name, ranges) => {
  if (ranges.length) window.CSS.highlights.set(name, new window.Highlight(...ranges));
  else window.CSS.highlights.delete(name);
};

const clearHighlights = () => {
  if (!window.CSS?.highlights) return;
  window.CSS.highlights.delete(HL_ALL);
  window.CSS.highlights.delete(HL_ACTIVE);
};

// У Range нет scrollIntoView, а ближайший к нему элемент может быть выше экрана
// целиком (длинный markdown-блок, diff) — поэтому ищем ближайшего прокручиваемого
// предка внутри модалки и центрируем совпадение в нём сами. Уже видимое
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
 * Состояние find-бара модалки: перехват Ctrl/Cmd+F, сбор совпадений, подсветка и
 * навигация prev/next. Разметку рисует ModalFindBar, подключает всё ModalShell.
 *
 * @param dialogRef  ref на бокс диалога — область поиска
 * @param active     открыта ли сама модалка
 * @param isTopmost  () => boolean, верхняя ли она в стопке (шорткат ловит только она)
 */
export default function useModalFind({ dialogRef, active, isTopmost }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [matches, setMatches] = useState(NO_MATCHES);
  const [index, setIndex] = useState(-1);
  const inputRef = useRef(null);

  // Модалку закрыли — бар к ней больше не относится, следующее открытие начинается с чистого.
  const [prevActive, setPrevActive] = useState(active);
  if (prevActive !== active) {
    setPrevActive(active);
    if (!active) {
      setOpen(false);
      setQuery('');
      setMatches(NO_MATCHES);
      setIndex(-1);
    }
  }

  // Пересбор совпадений по текущему DOM модалки. keepIndex — для пересбора после
  // правки содержимого: пользователь уже ушёл навигацией вперёд, и возврат на
  // первое совпадение выглядел бы как самопроизвольный скачок.
  const collect = useCallback(
    (q, { keepIndex = false } = {}) => {
      const root = dialogRef.current;
      const trimmed = q.trim();
      const ranges = root && trimmed ? collectMatchRanges(root, trimmed) : NO_MATCHES;
      setMatches(ranges);
      setIndex((prev) => {
        if (!ranges.length) return -1;
        return keepIndex && prev > 0 ? Math.min(prev, ranges.length - 1) : 0;
      });
    },
    [dialogRef],
  );

  const onQueryChange = useCallback(
    (q) => {
      setQuery(q);
      collect(q);
    },
    [collect],
  );

  const close = useCallback(() => {
    setOpen(false);
    setQuery('');
    setMatches(NO_MATCHES);
    setIndex(-1);
  }, []);

  const goNext = useCallback(() => {
    setIndex((i) => (matches.length ? (i + 1) % matches.length : -1));
  }, [matches.length]);

  const goPrev = useCallback(() => {
    setIndex((i) => (matches.length ? (i - 1 + matches.length) % matches.length : -1));
  }, [matches.length]);

  // Тело шортката — useEffectEvent: слушатель вешается один раз на открытие модалки,
  // но читает всегда свежие open/query.
  const onFindKey = useEffectEvent((e) => {
    if (!isTopmost()) return;
    // Гасим браузерный поиск по странице — ради этого всё и затевалось.
    e.preventDefault();
    setOpen(true);
    collect(query, { keepIndex: open });
    if (open) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  });

  useEffect(() => {
    if (!active) return undefined;
    const onKeyDown = (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      // e.code — физическая клавиша: на нелатинских раскладках e.key даёт символ
      // раскладки («а»), и проверка только по key ломает шорткат.
      if (e.key !== 'f' && e.key !== 'F' && e.code !== 'KeyF') return;
      onFindKey(e);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [active]);

  // Подсветка: активное совпадение — отдельным, более контрастным стилем.
  useEffect(() => {
    if (!window.CSS?.highlights) return undefined;
    if (!open || !matches.length) {
      clearHighlights();
      return undefined;
    }
    setHighlight(
      HL_ALL,
      matches.filter((_, i) => i !== index),
    );
    setHighlight(HL_ACTIVE, index >= 0 ? [matches[index]] : []);
    return clearHighlights;
  }, [open, matches, index]);

  useEffect(() => {
    const root = dialogRef.current;
    const range = index >= 0 ? matches[index] : null;
    if (root && range) scrollRangeIntoView(range, root);
  }, [dialogRef, matches, index]);

  // Содержимое модалки поменялось — старые Range указывают на выброшенные узлы.
  const recollect = useEffectEvent(() => collect(query, { keepIndex: true }));
  useEffect(() => {
    const root = dialogRef.current;
    if (!open || !root || typeof MutationObserver === 'undefined') return undefined;
    let timer = null;
    const observer = new MutationObserver((records) => {
      // Перерисовка самого бара (счётчик совпадений) — не повод пересобирать их заново.
      if (records.every((r) => inFindBar(r.target))) return;
      clearTimeout(timer);
      timer = setTimeout(recollect, RECOLLECT_MS);
    });
    observer.observe(root, { childList: true, subtree: true, characterData: true });
    return () => {
      clearTimeout(timer);
      observer.disconnect();
    };
  }, [dialogRef, open]);

  return {
    open,
    query,
    total: matches.length,
    activeIndex: index,
    inputRef,
    onQueryChange,
    close,
    goPrev,
    goNext,
  };
}
