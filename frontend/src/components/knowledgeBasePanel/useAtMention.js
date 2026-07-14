import { useState, useEffect, useRef, useCallback } from 'react';
import api from '../../api/documentsApi';

const DEBOUNCE_MS = 200;
const MIN_QUERY_LEN = 1;
const MAX_RESULTS = 10;

/**
 * Detects `@query` patterns in a textarea and fetches matching documents.
 *
 * Usage:
 *   const mention = useAtMention(textareaRef, val, (newVal, newCursor) => {
 *     // insert link into editor
 *   });
 *
 * Returns:
 *   {
 *     active    — boolean, whether the dropdown is visible
 *     results   — DocumentNode[]
 *     loading   — boolean
 *     query     — current search string after @
 *     anchorRect — DOMRect of the @ character position (for positioning dropdown)
 *     select(node) — call to insert the link and close
 *     dismiss()    — call to close without selecting
 *     handleKeyDown(e) — wire into the textarea onKeyDown
 *   }
 */
export default function useAtMention(textareaRef, value, onSelect) {
  const [active, setActive] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [anchorRect, setAnchorRect] = useState(null);
  const [selectedIdx, setSelectedIdx] = useState(0);

  // Track the position of the @ trigger in the textarea value
  const atPosRef = useRef(-1);
  const debounceRef = useRef(null);
  const abortRef = useRef(null);

  // ── Dismiss / select ───────────────────────────────────────────────────────
  // Defined BEFORE the keyboard handler so it can list them as dependencies
  // (referencing a `const` in a deps array before its declaration is a TDZ
  // error). Keeping them in the deps array also fixes the stale-closure bug
  // where Enter inserted using a `value`/`onSelect` captured on an older render.

  const dismiss = useCallback(() => {
    setActive(false);
    setResults([]);
    setQuery('');
    atPosRef.current = -1;
    clearTimeout(debounceRef.current);
    abortRef.current?.abort();
  }, []);

  const select = useCallback(
    (node) => {
      const ta = textareaRef.current;
      if (!ta || atPosRef.current === -1) return;

      const before = value.slice(0, atPosRef.current);
      const afterCursor = ta.selectionStart;
      const after = value.slice(afterCursor);

      // Markdown link: [Title](/?doc=ID)
      const link = `[${node.title}](/?doc=${node.id})`;
      const newVal = before + link + after;
      const newCursor = before.length + link.length;

      onSelect(newVal, newCursor);
      dismiss();
    },
    [textareaRef, value, onSelect, dismiss],
  );

  // ── Debounced fetch ─────────────────────────────────────────────────────────

  const doSearch = useCallback(async (q) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    try {
      const data = await api.searchByName(q, MAX_RESULTS, controller.signal);
      setResults(Array.isArray(data) ? data.slice(0, MAX_RESULTS) : []);
    } catch (err) {
      if (err.name !== 'AbortError') setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const scheduleSearch = useCallback(
    (q) => {
      clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => doSearch(q), DEBOUNCE_MS);
    },
    [doSearch],
  );

  // ── Detect @mention in textarea ──────────────────────────────────────────────

  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;

    const cursor = ta.selectionStart ?? 0;
    const textBefore = value.slice(0, cursor);

    // Find the last @ that:
    // - is at start of string, or preceded by whitespace/newline
    // - has no whitespace after it (still typing)
    const match = textBefore.match(/(?:^|[\s\n])@([^\s@]*)$/);

    if (match) {
      const q = match[1];
      const atIndex = textBefore.lastIndexOf('@');
      atPosRef.current = atIndex;
      setQuery(q);
      setSelectedIdx(0);

      if (q.length >= MIN_QUERY_LEN) {
        setActive(true);
        setAnchorRect(getCaretRect(ta, atIndex));
        scheduleSearch(q);
      } else if (q.length === 0) {
        // Just typed @, show nothing yet but keep active
        setActive(true);
        setAnchorRect(getCaretRect(ta, atIndex));
        setResults([]);
      }
    } else {
      dismiss();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  // ── Keyboard navigation (wired into textarea onKeyDown) ──────────────────────

  const handleKeyDown = useCallback(
    (e) => {
      if (!active) return;

      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIdx((i) => Math.min(i + 1, results.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIdx((i) => Math.max(i - 1, 0));
      } else if (e.key === 'Enter' && results.length > 0) {
        e.preventDefault();
        select(results[selectedIdx]);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        dismiss();
      }
    },
    [active, results, selectedIdx, select, dismiss],
  );

  return {
    active,
    results,
    loading,
    query,
    anchorRect,
    selectedIdx,
    select,
    dismiss,
    handleKeyDown,
  };
}

// ── Caret position helper ────────────────────────────────────────────────────

/**
 * Approximates the DOMRect of a character at `index` in a textarea.
 * Uses a hidden mirror div replicating the textarea's styles.
 * Returns null if the element is not in the DOM.
 */
function getCaretRect(textarea, index) {
  try {
    const mirror = document.createElement('div');
    const style = window.getComputedStyle(textarea);

    const props = [
      'fontFamily',
      'fontSize',
      'fontWeight',
      'fontStyle',
      'letterSpacing',
      'lineHeight',
      'padding',
      'paddingTop',
      'paddingRight',
      'paddingBottom',
      'paddingLeft',
      'borderTopWidth',
      'borderRightWidth',
      'borderBottomWidth',
      'borderLeftWidth',
      'boxSizing',
      'whiteSpace',
      'wordBreak',
      'overflowWrap',
      'tabSize',
    ];
    props.forEach((p) => (mirror.style[p] = style[p]));
    mirror.style.width = textarea.offsetWidth + 'px';
    mirror.style.height = 'auto';
    mirror.style.position = 'absolute';
    mirror.style.visibility = 'hidden';
    mirror.style.whiteSpace = 'pre-wrap';
    mirror.style.overflow = 'hidden';

    const textBefore = textarea.value.slice(0, index);
    mirror.textContent = textBefore;

    const span = document.createElement('span');
    span.textContent = '@';
    mirror.appendChild(span);

    document.body.appendChild(mirror);
    const taRect = textarea.getBoundingClientRect();
    const spanRect = span.getBoundingClientRect();

    // Adjust for scroll
    const scrollTop = textarea.scrollTop;
    const result = {
      top: taRect.top + spanRect.top - mirror.getBoundingClientRect().top - scrollTop,
      left: taRect.left + spanRect.left - mirror.getBoundingClientRect().left,
      width: spanRect.width,
      height: spanRect.height,
      bottom: 0,
      right: 0,
    };
    result.bottom = result.top + result.height;

    document.body.removeChild(mirror);
    return result;
  } catch {
    // Fallback: position below the textarea
    const rect = textarea.getBoundingClientRect();
    return { top: rect.bottom + 4, left: rect.left, width: 0, height: 0, bottom: rect.bottom + 4 };
  }
}
