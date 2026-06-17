import React, { useRef, useEffect, useCallback, useState, useImperativeHandle, forwardRef } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import { makeToken, makeRefToken, parseToken, baseName, fetchContent, TOKEN_RE } from './fileChips';
import FilePickerDropdown from './FilePickerDropdown';
import useEscape from '../common/useEscape';
import { IconX } from '../../icons';

const DEBOUNCE_MS = 200;
const TRIGGER = '/file ';
const TRIGGER_RE = /(?:^|\s)\/file (\S*)$/;

// ── Сериализация DOM ⇄ плоская строка с токенами ───────────────────────────────

function serializeNode(node) {
  if (node.nodeType === Node.TEXT_NODE) return node.nodeValue;
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  if (node.classList?.contains('file-chip')) return node.dataset.token || '';
  if (node.tagName === 'BR') return '\n';
  let inner = '';
  node.childNodes.forEach((c) => (inner += serializeNode(c)));
  return /^(DIV|P)$/.test(node.tagName) ? '\n' + inner : inner;
}

function serialize(root) {
  let out = '';
  root.childNodes.forEach((c) => (out += serializeNode(c)));
  return out.replace(/^\n/, '');
}

/** Построить DOM-элемент чипа из строки-токена. */
function makeChipEl(token) {
  const parsed = parseToken(token);
  const path = parsed?.path ?? token;
  const range = parsed?.from != null ? `:${parsed.from}-${parsed.to}` : '';
  const refOnly = parsed?.refOnly ?? false;

  const chip = document.createElement('span');
  chip.className = 'file-chip' + (refOnly ? ' file-chip--ref' : '');
  chip.contentEditable = 'false';
  chip.dataset.token = token;
  chip.dataset.path = path;
  chip.title = path + range;

  const icon = document.createElement('span');
  icon.className = 'file-chip__icon';
  icon.textContent = refOnly ? '📎' : '📄';

  const label = document.createElement('span');
  label.className = 'file-chip__label';
  label.textContent = baseName(path) + range;

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'file-chip__remove';
  remove.textContent = '×';
  remove.tabIndex = -1;

  chip.append(icon, label, remove);
  return chip;
}

/** Отрисовать плоскую строку value в DOM editor (текстовые узлы + чипы). */
function renderValue(root, value) {
  root.textContent = '';
  let last = 0;
  for (const m of value.matchAll(TOKEN_RE)) {
    if (m.index > last) root.appendChild(document.createTextNode(value.slice(last, m.index)));
    root.appendChild(makeChipEl(m[0]));
    last = m.index + m[0].length;
  }
  if (last < value.length) root.appendChild(document.createTextNode(value.slice(last)));
}

function placeCaretEnd(root) {
  const sel = window.getSelection();
  const range = document.createRange();
  range.selectNodeContents(root);
  range.collapse(false);
  sel.removeAllRanges();
  sel.addRange(range);
}

// ── Компонент ─────────────────────────────────────────────────────────────────

const FileChipInput = forwardRef(function FileChipInput(
  { value, onChange, onSend, disabled, placeholder, chatId },
  ref,
) {
  const { t } = useTranslation('chat');
  const editorRef = useRef(null);
  const internalRef = useRef(value);
  const triggerRef = useRef(null);

  const [picker, setPicker] = useState({ open: false, query: '', results: [], loading: false, anchor: null, idx: 0 });
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

  const runSearch = useCallback((q) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setPicker((p) => ({ ...p, loading: true }));
    gitApi
      .searchFiles(q, 10, controller.signal)
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
    const m = before.match(TRIGGER_RE);
    if (!m) return dismissPicker();

    const query = m[1];
    const start = range.startOffset - TRIGGER.length - query.length;
    triggerRef.current = { node, start, query };

    const rect = range.getBoundingClientRect();
    const anchor = rect && (rect.top || rect.left) ? { top: rect.top, left: rect.left } : null;
    setPicker((p) => ({ ...p, open: true, query, anchor, idx: 0 }));

    clearTimeout(debounceTimer.current);
    if (query.length >= 1) {
      debounceTimer.current = setTimeout(() => runSearch(query), DEBOUNCE_MS);
    } else {
      setPicker((p) => ({ ...p, results: [], loading: false }));
    }
  }, [dismissPicker, runSearch]);

  const emitChange = useCallback(() => {
    const v = serialize(editorRef.current);
    internalRef.current = v;
    onChange(v);
  }, [onChange]);

  const handleInput = useCallback(() => {
    emitChange();
    detectTrigger();
    setPreview((pv) => (pv ? null : pv));
  }, [emitChange, detectTrigger]);

  const insertFile = useCallback(
    (fileNode) => {
      const trig = triggerRef.current;
      const root = editorRef.current;
      if (!trig || !root) return;
      const { node, start, query } = trig;

      const before = node.nodeValue.slice(0, start);
      const after = node.nodeValue.slice(start + TRIGGER.length + query.length);

      const chip = makeChipEl(makeToken(fileNode.path));
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

  const insertTextAtCaret = useCallback((text) => {
    const sel = window.getSelection();
    if (!sel.rangeCount) return;
    const range = sel.getRangeAt(0);
    range.deleteContents();
    const tn = document.createTextNode(text);
    range.insertNode(tn);
    range.setStart(tn, tn.length);
    range.collapse(true);
    sel.removeAllRanges();
    sel.addRange(range);
  }, []);

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
          insertFile(picker.results[picker.idx]);
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
        insertTextAtCaret('\n');
        emitChange();
      }
    },
    [picker.open, picker.results, picker.idx, insertFile, dismissPicker, disabled, onSend, insertTextAtCaret, emitChange],
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
        const parsed = parseToken(chip.dataset.token);
        if (!parsed) return;
        const rect = chip.getBoundingClientRect();
        setPreview({ ...parsed, rect, chipEl: chip, loading: !parsed.refOnly, data: null, error: false });
        if (!parsed.refOnly) {
          fetchContent(parsed.path, parsed.from, parsed.to)
            .then((data) => setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, data } : pv)))
            .catch(() => setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, error: true } : pv)));
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
          onSelect={insertFile}
          onDismiss={dismissPicker}
        />
      )}

      {preview && (
        <FileChipPreview
          preview={preview}
          onClose={() => setPreview(null)}
          onToggleRef={handleToggleRef}
          closeLabel={t('fileInput.closePreview')}
          loadingLabel={t('fileInput.searching')}
          errorLabel={t('fileInput.previewError')}
          usePathOnlyLabel={t('fileInput.usePathOnly')}
          useFullContentLabel={t('fileInput.useFullContent')}
        />
      )}
    </>
  );
});

// ── Превью содержимого файла — полноэкранная модалка ─────────────────────────

function FileChipPreview({ preview, onClose, onToggleRef, closeLabel, loadingLabel, errorLabel, usePathOnlyLabel, useFullContentLabel }) {
  const { path, from, to, refOnly, loading, data, error } = preview;
  useEscape(onClose);
  const name = baseName(path);
  const range = from != null ? ` (${from}–${to})` : '';

  return createPortal(
    <div className="fs-editor-overlay" onMouseDown={onClose}>
      <div className="fs-editor file-preview-modal" onMouseDown={(e) => e.stopPropagation()}>
        <div className="fs-editor__head">
          <div className="file-preview-modal__title">
            <span className="file-preview-modal__name">{name}{range}</span>
            <span className="file-preview-modal__path" title={path}>{path}</span>
          </div>
          <button
            type="button"
            className={'file-preview-modal__toggle' + (refOnly ? ' file-preview-modal__toggle--active' : '')}
            onClick={onToggleRef}
            title={refOnly ? useFullContentLabel : usePathOnlyLabel}
          >
            {refOnly ? '📄' : '📎'}
          </button>
          <button className="fs-editor__close" title={closeLabel} onClick={onClose}>
            <IconX />
          </button>
        </div>
        <div className="fs-editor__body file-preview-modal__body">
          {!refOnly && (
            <>
              {loading && <div className="file-preview-modal__msg">{loadingLabel}</div>}
              {error && <div className="file-preview-modal__msg">{errorLabel}</div>}
              {!loading && !error && data?.binary && <div className="file-preview-modal__msg">[бинарный файл]</div>}
              {!loading && !error && !data?.binary && (
                <pre className="file-preview-modal__code">{data?.content ?? ''}</pre>
              )}
            </>
          )}
          {refOnly && (
            <div className="file-preview-modal__ref-note">{path}</div>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}

export default FileChipInput;
