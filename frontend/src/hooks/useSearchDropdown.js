import { useCallback, useEffect, useRef, useState } from 'react';

const DEFAULT_DEBOUNCE_MS = 200;

/**
 * Shared state machine for a "lupa button → floating results dropdown" search
 * widget: debounced abortable search, arrow-key navigation, click-outside and
 * scroll/resize dismissal, scroll-into-view of the selected row. Extracted
 * from ChatSearch (chat sidebar) and FileSearch (file browser), which were
 * near-identical copies of this exact machinery, differing only in the fetch
 * call and the row markup — both stay in the component.
 *
 * `search` is read through a ref, so callers can pass a fresh inline callback
 * on every render without retriggering the debounce/abort plumbing.
 *
 * @param {(query: string, signal: AbortSignal) => Promise<any[]>} search
 * @param {{ debounceMs?: number }} [options]
 */
export default function useSearchDropdown(search, { debounceMs = DEFAULT_DEBOUNCE_MS } = {}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [idx, setIdx] = useState(0);
  const [anchorRect, setAnchorRect] = useState(null);

  const wrapRef = useRef(null);
  const inputRef = useRef(null);
  const listRef = useRef(null);
  // Ref to the floating portal root — lets click-outside/scroll checks ignore
  // clicks/scrolls inside the dropdown without coupling to a specific class name.
  const portalRef = useRef(null);
  const debounceRef = useRef(null);
  const abortRef = useRef(null);
  const searchRef = useRef(search);
  searchRef.current = search;

  const close = useCallback(() => {
    clearTimeout(debounceRef.current);
    abortRef.current?.abort();
    setOpen(false);
    setQuery('');
    setResults([]);
    setIdx(0);
    setLoading(false);
  }, []);

  const openSearch = useCallback(() => {
    setOpen(true);
    requestAnimationFrame(() => inputRef.current?.focus());
  }, []);

  // Якорь дропдауна — позиция инпута на момент открытия.
  useEffect(() => {
    if (open) setAnchorRect(inputRef.current?.getBoundingClientRect() ?? null);
  }, [open]);

  const runSearch = useCallback((q) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    searchRef
      .current(q, controller.signal)
      .then((data) => {
        setResults(Array.isArray(data) ? data : []);
        setIdx(0);
        setLoading(false);
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          setResults([]);
          setLoading(false);
        }
      });
  }, []);

  const handleChange = useCallback(
    (e) => {
      const q = e.target.value;
      setQuery(q);
      clearTimeout(debounceRef.current);
      if (q.trim().length >= 1) {
        debounceRef.current = setTimeout(() => runSearch(q.trim()), debounceMs);
      } else {
        abortRef.current?.abort();
        setResults([]);
        setLoading(false);
      }
    },
    [runSearch, debounceMs],
  );

  /** Wire into the input's onKeyDown: handleKeyDown(e, (item) => ...). */
  const handleKeyDown = useCallback(
    (e, onChoose) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setIdx((i) => Math.min(i + 1, results.length - 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setIdx((i) => Math.max(i - 1, 0));
        return;
      }
      if (e.key === 'Enter' && results.length > 0) {
        e.preventDefault();
        onChoose(results[idx]);
      }
    },
    [close, results, idx],
  );

  // Клик снаружи (кнопки/инпута И плавающего списка) закрывает поиск.
  useEffect(() => {
    if (!open) return undefined;
    const onDocMouseDown = (e) => {
      if (wrapRef.current?.contains(e.target)) return;
      if (portalRef.current?.contains(e.target)) return;
      close();
    };
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open, close]);

  // Якорь фиксируется один раз при открытии и не следит за окном — при ресайзе
  // или скролле список «отрывается» от поля. Дешевле закрыть поиск, чем
  // пересчитывать позицию на каждый scroll/resize. Прокрутка внутри самого
  // списка результатов (колёсиком или scrollIntoView при навигации стрелками)
  // якорь не двигает — её игнорируем.
  useEffect(() => {
    if (!open) return undefined;
    const onScroll = (e) => {
      if (e.target instanceof Element && portalRef.current?.contains(e.target)) return;
      close();
    };
    window.addEventListener('resize', close);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open, close]);

  useEffect(() => {
    listRef.current?.children[idx]?.scrollIntoView({ block: 'nearest' });
  }, [idx]);

  return {
    open,
    query,
    results,
    loading,
    idx,
    anchorRect,
    wrapRef,
    inputRef,
    listRef,
    portalRef,
    setIdx,
    openSearch,
    close,
    handleChange,
    handleKeyDown,
  };
}
