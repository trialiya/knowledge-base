// ─── Search picker for /file и /doc триггеров ────────────────────────────────
// Хук владеет всем выпадающим списком чипа: детектирование триггера у каретки,
// дебаунс-поиск с отменой, навигация по результатам и вставка выбранного чипом
// в поле. Триггер наружу не отдаётся — на нём держится и поиск, и вставка, и
// разошлись бы они молча.

import { useState, useRef, useCallback } from 'react';
import { detectTriggerInText, tokenForItem, TRIGGER_TYPES } from './chipTriggers';
import { makeChipEl } from './fileChipEditorDom';

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

/**
 * @param project репозиторий активного чата — в нём и ищет `/file`. Обычный
 *   параметр, а не зеркало в рефе: значение просто входит в зависимости
 *   runSearch, иначе колбэк застрял бы на проекте, открытом при монтировании.
 * @param editorRef поле композера: вставка чипа правит его DOM на месте.
 * @param emitChange снять значение с поля после вставки.
 */
export default function useChipPicker(project, editorRef, emitChange) {
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

  const runSearch = useCallback(
    (q, type) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setPicker((p) => ({ ...p, loading: true }));
      TRIGGER_TYPES[type]
        .search(q, controller.signal, project)
        .then((data) => setPicker((p) => ({ ...p, loading: false, results: Array.isArray(data) ? data : [], idx: 0 })))
        .catch((err) => {
          if (err.name !== 'AbortError') setPicker((p) => ({ ...p, loading: false, results: [] }));
        });
    },
    [project],
  );

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
  // Проект уезжает в токен: чип должен помнить репозиторий, в котором его нашли,
  // а не тот, что окажется выбран, когда сообщение наконец отправят.
  const tokenFor = useCallback(
    (item, withContent) => tokenForItem(triggerRef.current?.type, item, withContent, project),
    [project],
  );

  // Заменить набранный триггер чипом. Правим DOM на месте, а не через renderValue:
  // та пересобирает поле целиком и стирает нативный стек отмены.
  const doInsert = useCallback(
    (token) => {
      const trig = triggerRef.current;
      const root = editorRef?.current;
      if (!trig || !root) return;
      const { node, start, cursorOffset } = trig;

      const before = node.nodeValue.slice(0, start);
      const after = node.nodeValue.slice(cursorOffset);

      const chip = makeChipEl(token, project);
      const tail = document.createTextNode(' ' + after);
      node.nodeValue = before;
      node.after(chip, tail);

      const sel = window.getSelection();
      const range = document.createRange();
      range.setStart(tail, 1);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);

      dismissPicker();
      emitChange();
      root.focus();
    },
    [editorRef, dismissPicker, emitChange, project],
  );

  /** Вставить ссылку (Enter / клик по строке). */
  const insertItem = useCallback((item) => doInsert(tokenFor(item, false)), [doInsert, tokenFor]);
  /** Вставить с содержимым (кнопка в строке). */
  const insertItemWithContent = useCallback((item) => doInsert(tokenFor(item, true)), [doInsert, tokenFor]);

  return { picker, detectTrigger, dismissPicker, moveSelection, insertItem, insertItemWithContent };
}
