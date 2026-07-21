import React, { useRef, useEffect, useCallback, useState, useImperativeHandle, forwardRef } from 'react';
import gitApi from '../../api/gitApi';
import documentsApi from '../../api/documentsApi';
import {
  makeToken,
  makeRefToken,
  makeDocToken,
  makeDocRefToken,
  parseToken,
  parseDocToken,
  parseDocRefToken,
  baseName,
  fetchContent,
} from './fileChips';
import {
  serialize,
  makeChipEl,
  renderValue,
  placeCaretEnd,
  normalizeTrailingSentinel,
} from './fileChipEditorDom';
import FilePickerDropdown from './FilePickerDropdown';
import FileChipPreview from './FileChipPreview';

const DEBOUNCE_MS = 200;
const FILE_TRIGGER = '/file';
const FILE_TRIGGER_RE = /(?:^|\s)\/file\s*(\S*)$/;
const DOC_TRIGGER = '/doc';
const DOC_TRIGGER_RE = /(?:^|\s)\/doc\s*(\S*)$/;

async function searchDocsAsync(q, signal) {
  const isNumeric = q.length > 0 && /^\d+$/.test(q);
  if (!isNumeric) {
    return documentsApi.searchByName(q, 10, signal);
  }
  const [nameRes, idRes] = await Promise.all([
    documentsApi.searchByName(q, 10, signal).catch((e) => {
      if (e.name === 'AbortError') throw e;
      return [];
    }),
    documentsApi.fetchById(Number(q), signal).catch((e) => {
      if (e.name === 'AbortError') throw e;
      return null;
    }),
  ]);
  const results = Array.isArray(nameRes) ? [...nameRes] : [];
  if (idRes && !results.find((r) => r.id === idRes.id)) {
    results.unshift(idRes);
  }
  return results;
}

// ── Компонент ─────────────────────────────────────────────────────────────────

const FileChipInput = forwardRef(function FileChipInput(
  { value, onChange, onSend, disabled, placeholder, chatId },
  ref,
) {
  const editorRef = useRef(null);
  const internalRef = useRef(value);
  const triggerRef = useRef(null);

  const [picker, setPicker] = useState({
    open: false,
    query: '',
    results: [],
    loading: false,
    anchor: null,
    idx: 0,
    type: 'file',
  });
  const [preview, setPreview] = useState(null); // { path, from, to, refOnly, rect, chipEl, data, loading, error }

  const debounceTimer = useRef(null);
  const abortRef = useRef(null);

  useImperativeHandle(ref, () => ({
    focus: () => editorRef.current?.focus(),
  }));

  // Закрываем превью при переключении чата.
  useEffect(() => {
    setPreview(null);
  }, [chatId]);

  useEffect(() => {
    if (value === internalRef.current) return;
    internalRef.current = value;
    const root = editorRef.current;
    if (!root) return;
    renderValue(root, value);
    if (document.activeElement === root) placeCaretEnd(root);
  }, [value]);

  useEffect(() => {
    if (editorRef.current) renderValue(editorRef.current, value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    const search =
      type === 'doc' ? searchDocsAsync(q, controller.signal) : gitApi.searchFiles(q, 10, controller.signal);
    search
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

    let m = before.match(FILE_TRIGGER_RE);
    let type = 'file';
    if (!m) {
      m = before.match(DOC_TRIGGER_RE);
      type = 'doc';
    }
    if (!m) return dismissPicker();

    const query = m[1];
    const commandStr = type === 'file' ? FILE_TRIGGER : DOC_TRIGGER;
    const start = before.lastIndexOf(commandStr);

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

  const emitChange = useCallback(() => {
    const root = editorRef.current;
    if (!root) return;
    // Приводим хвостовой sentinel-<br> в порядок на каждом изменении: он нужен
    // ровно тогда, когда контент заканчивается переносом. Так живой DOM всегда
    // совпадает с тем, что нарисует renderValue из сохранённого значения.
    normalizeTrailingSentinel(root);
    const v = serialize(root);
    internalRef.current = v;
    onChange(v);
  }, [onChange]);

  const handleInput = useCallback(() => {
    emitChange();
    // Chrome auto-inserts a bare <br> when all content is deleted. serialize()
    // strips the leading \n so value becomes "" — but the <br> (плюс sentinel,
    // который навесил emitChange) остаётся в DOM, и курсор оказывается на пустой
    // строке поверх placeholder. Если значение пустое, а в редакторе остались
    // только <br> (обычные и/или sentinel) — очищаем. Это путь именно input:
    // Shift+Enter сюда не заходит (не порождает input), поэтому осознанный первый
    // перенос строки sentinel сохраняет и он виден сразу.
    if (!internalRef.current && editorRef.current) {
      const root = editorRef.current;
      if ([...root.childNodes].every((n) => n.nodeName === 'BR')) {
        root.textContent = '';
      }
    }
    detectTrigger();
    setPreview((pv) => (pv ? null : pv));
  }, [emitChange, detectTrigger]);

  const doInsert = useCallback(
    (token) => {
      const trig = triggerRef.current;
      const root = editorRef.current;
      if (!trig || !root) return;
      const { node, start, cursorOffset } = trig;

      const before = node.nodeValue.slice(0, start);
      const after = node.nodeValue.slice(cursorOffset);

      const chip = makeChipEl(token);
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
    [dismissPicker, emitChange],
  );

  // Вставить ссылку (по умолчанию: Enter / клик по строке)
  const insertItem = useCallback(
    (item) => {
      const type = triggerRef.current?.type;
      doInsert(type === 'doc' ? makeDocRefToken(item.id, item.title) : makeRefToken(item.path));
    },
    [doInsert],
  );

  // Вставить с содержимым (кнопка в дропдауне)
  const insertItemWithContent = useCallback(
    (item) => {
      const type = triggerRef.current?.type;
      doInsert(type === 'doc' ? makeDocToken(item.id, item.title) : makeToken(item.path));
    },
    [doInsert],
  );

  // Сбрасываем форматирование при вставке — вставляем только plain text.
  // Используем execCommand('insertText') (а не ручную вставку через Range), чтобы:
  //  1) вставка попадала в нативный стек отмены — Ctrl+Z отменяет вставленный текст;
  //  2) браузер сам оформлял переносы строк, включая filler для последней/пустой
  //     строки — она отображается сразу после вставки. При ручной вставке хвостовой
  //     <br> был невидимым, и следующий Shift+Enter «проявлял» его, что выглядело
  //     как двойной перевод строки. serialize() уже понимает такой DOM (блок с
  //     единственным <br> → один '\n').
  const handlePaste = useCallback(
    (e) => {
      e.preventDefault();
      const text = (e.clipboardData || window.clipboardData).getData('text/plain');
      if (!text) return;
      document.execCommand('insertText', false, text);
      emitChange();
    },
    [emitChange],
  );

  const handleKeyDown = useCallback(
    (e) => {
      if (picker.open) {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          setPicker((p) => ({ ...p, idx: Math.min(p.idx + 1, p.results.length - 1) }));
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          setPicker((p) => ({ ...p, idx: Math.max(p.idx - 1, 0) }));
          return;
        }
        if (e.key === 'Enter' && picker.results.length > 0) {
          e.preventDefault();
          insertItem(picker.results[picker.idx]);
          return;
        }
        if (e.key === 'Escape') {
          e.preventDefault();
          dismissPicker();
          return;
        }
      }

      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!disabled) {
          setPreview(null);
          onSend();
        }
      } else if (e.key === 'Enter' && e.shiftKey) {
        e.preventDefault();
        const sel2 = window.getSelection();
        if (sel2?.rangeCount) {
          const r2 = sel2.getRangeAt(0);
          r2.deleteContents();

          const br = document.createElement('br');
          r2.insertNode(br);

          // Ставим курсор сразу после нового <br>. Sentinel (filler для видимой
          // пустой строки) добавит emitChange → normalizeTrailingSentinel, но
          // только если <br> оказался хвостовым; если за ним есть контент, он сам
          // рисует новую строку и лишний sentinel не создаёт второй пустой строки.
          const newRange = document.createRange();
          newRange.setStartAfter(br);
          newRange.collapse(true);
          sel2.removeAllRanges();
          sel2.addRange(newRange);

          emitChange();

          // Scroll the editor so the cursor line is visible.
          // A collapsed range after a <br> returns zero rects, so we measure
          // via a temporary inline span inserted at the cursor position.
          requestAnimationFrame(() => {
            const editorEl = editorRef.current;
            if (!editorEl) return;
            const tmp = document.createElement('span');
            br.after(tmp);
            const tmpRect = tmp.getBoundingClientRect();
            tmp.remove();
            const editorRect = editorEl.getBoundingClientRect();
            if (tmpRect.bottom > editorRect.bottom - 4) {
              editorEl.scrollTop += tmpRect.bottom - editorRect.bottom + 10;
            }
          });
        } else {
          emitChange();
        }
      }
    },
    [picker.open, picker.results, picker.idx, insertItem, dismissPicker, disabled, onSend, emitChange],
  );

  // Переключение чипа между режимами «содержимое» и «только путь».
  const handleToggleRef = useCallback(() => {
    setPreview((pv) => {
      if (!pv) return pv;
      const { chipEl, path, from, to, refOnly } = pv;
      const newRefOnly = !refOnly;
      const newToken = newRefOnly ? makeRefToken(path) : makeToken(path, from, to);
      chipEl.dataset.token = newToken;
      const label = chipEl.querySelector('.file-chip__label');
      const range = from != null ? `:${from}-${to}` : '';
      if (label) label.textContent = baseName(path) + range;
      const icon = chipEl.querySelector('.file-chip__icon');
      if (icon) icon.textContent = newRefOnly ? '📎' : '📄';
      if (newRefOnly) chipEl.classList.add('file-chip--ref');
      else chipEl.classList.remove('file-chip--ref');
      // emitChange внутри setPreview вызвать нельзя, откладываем
      return { ...pv, refOnly: newRefOnly };
    });
    // emitChange здесь — после обновления DOM чипа
    setTimeout(() => emitChange(), 0);
  }, [emitChange]);

  const handleClick = useCallback(
    (e) => {
      const removeBtn = e.target.closest?.('.file-chip__remove');
      if (removeBtn) {
        e.preventDefault();
        const chip = removeBtn.closest('.file-chip');
        chip?.remove();
        emitChange();
        setPreview(null);
        return;
      }
      const chip = e.target.closest?.('.file-chip');
      if (chip) {
        e.preventDefault();
        if (parseDocToken(chip.dataset.token) || parseDocRefToken(chip.dataset.token)) return;
        const parsed = parseToken(chip.dataset.token);
        if (!parsed) return;
        const rect = chip.getBoundingClientRect();
        setPreview({ ...parsed, rect, chipEl: chip, loading: !parsed.refOnly, data: null, error: false });
        if (!parsed.refOnly) {
          fetchContent(parsed.path, parsed.from, parsed.to)
            .then((data) => setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, data } : pv)))
            .catch(() =>
              setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, error: true } : pv)),
            );
        }
      }
    },
    [emitChange],
  );

  const isEmpty = !value || value.length === 0;

  return (
    <>
      <div
        ref={editorRef}
        className={`message-input message-input--rich${isEmpty ? ' is-empty' : ''}`}
        contentEditable={!disabled}
        suppressContentEditableWarning
        role="textbox"
        aria-multiline="true"
        data-placeholder={placeholder}
        onInput={handleInput}
        onKeyDown={handleKeyDown}
        onPaste={handlePaste}
        onClick={handleClick}
        onBlur={() => setTimeout(() => dismissPicker(), 120)}
      />

      {picker.open && (
        <FilePickerDropdown
          results={picker.results}
          loading={picker.loading}
          query={picker.query}
          anchorRect={picker.anchor}
          selectedIdx={picker.idx}
          onSelect={insertItem}
          onSelectWithContent={insertItemWithContent}
          onDismiss={dismissPicker}
          type={picker.type}
        />
      )}

      {preview && <FileChipPreview preview={preview} onClose={() => setPreview(null)} onToggleRef={handleToggleRef} />}
    </>
  );
});

export default FileChipInput;
