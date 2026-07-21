// ─── Search picker for /file и /doc триггеров ────────────────────────────────
// Хук владеет всем состоянием и async'ом выпадающего списка: детектирование
// триггера у каретки, дебаунс-поиск с отменой, навигация по результатам и
// разрешение выбранного элемента в токен. DOM-вставку чипа делает сам компонент
// (ему принадлежит редактор), а хук только отдаёт триггер и токен.

import { useState, useRef, useCallback } from 'react';
import { detectTriggerInText, tokenForItem, TRIGGER_TYPES } from './chipTriggers';

const DEBOUNCE_MS = 200;

const INITIAL = {
  open: false,
  query: '',
  results: [],
  loading: false,
  anchor: null,
  idx: 0,
  type: 'file',
};

export default function useChipPicker() {
  const [picker, setPicker] = useState(INITIAL);
  // Триггер, вокруг которого откроется список: узел, границы команды и тип.
  const triggerRef = useRef(null);
  const debounceTimer = useRef(null);
  const abortRef = useRef(null);

  const dismissPicker = useCallback(() => {
    triggerRef.current = null;
    clearTimeout(debounceTimer.current);
    abortRef.current?.abort();
    setPicker((p) => (p.open ? { ...p, open: false, results: [], query: '' } : p));
  }, []);

  const runSearch = useCallback((q, type) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setPicker((p) => ({ ...p, loading: true }));
    TRIGGER_TYPES[type]
      .search(q, controller.signal)
      .then((data) => setPicker((p) => ({ ...p, loading: false, results: Array.isArray(data) ? data : [], idx: 0 })))
      .catch((err) => {
        if (err.name !== 'AbortError') setPicker((p) => ({ ...p, loading: false, results: [] }));
      });
  }, []);

  const detectTrigger = useCallback(() => {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return dismissPicker();
    const range = sel.getRangeAt(0);
    const node = range.startContainer;
    if (node.nodeType !== Node.TEXT_NODE) return dismissPicker();

    const before = node.nodeValue.slice(0, range.startOffset);
    const hit = detectTriggerInText(before);
    if (!hit) return dismissPicker();

    const { type, query, start } = hit;
    triggerRef.current = { node, start, cursorOffset: range.startOffset, query, type };

    const rect = range.getBoundingClientRect();
    const anchor = rect && (rect.top || rect.left) ? { top: rect.top, left: rect.left } : null;
    setPicker((p) => ({ ...p, open: true, query, anchor, idx: 0, type }));

    clearTimeout(debounceTimer.current);
    if (query.length >= 1) {
      debounceTimer.current = setTimeout(() => runSearch(query, type), DEBOUNCE_MS);
    } else {
      setPicker((p) => ({ ...p, results: [], loading: false }));
    }
  }, [dismissPicker, runSearch]);

  // Сдвиг выделения по списку (delta = +1 / -1), с зажимом в границах.
  const moveSelection = useCallback((delta) => {
    setPicker((p) => ({ ...p, idx: Math.min(Math.max(p.idx + delta, 0), p.results.length - 1) }));
  }, []);

  // Токен для выбранного элемента по типу текущего триггера.
  const tokenFor = useCallback((item, withContent) => tokenForItem(triggerRef.current?.type, item, withContent), []);

  return { picker, triggerRef, detectTrigger, dismissPicker, moveSelection, tokenFor };
}
