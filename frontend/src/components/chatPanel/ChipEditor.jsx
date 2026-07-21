import React, { useRef, useEffect, useCallback, useImperativeHandle, forwardRef } from 'react';
import { serialize, makeChipEl, renderValue, placeCaretEnd, normalizeTrailingSentinel } from './fileChipEditorDom';
import FilePickerDropdown from './FilePickerDropdown';
import FileChipPreview from './FileChipPreview';
import RichTextEditor from './RichTextEditor';
import useChipPicker from './useChipPicker';
import useChipPreview from './useChipPreview';

// ── Компонент ─────────────────────────────────────────────────────────────────
// Композер чата: rich-text редактор с чипами файлов и документов (/file, /doc).
// Владеет переходами value ⇄ DOM, вводом, клавиатурой, вставкой plain-text и
// вставкой чипа в DOM; сам div рендерит RichTextEditor. Логику выпадающего
// списка и превью чипа держат хуки useChipPicker / useChipPreview.

const ChipEditor = forwardRef(function ChipEditor({ value, onChange, onSend, disabled, placeholder, chatId }, ref) {
  const editorRef = useRef(null);
  const internalRef = useRef(value);

  const { picker, triggerRef, detectTrigger, dismissPicker, moveSelection, tokenFor } = useChipPicker();

  useImperativeHandle(ref, () => ({
    focus: () => editorRef.current?.focus(),
  }));

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

  const {
    preview,
    openFromChip,
    toggleRef,
    close: closePreview,
    clear: clearPreview,
  } = useChipPreview({
    chatId,
    onAfterToggle: emitChange,
  });

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
      const ignorable = (n) => n.nodeName === 'BR' || (n.nodeType === Node.TEXT_NODE && !n.nodeValue);
      if ([...root.childNodes].every(ignorable)) {
        root.textContent = '';
      }
    }
    detectTrigger();
    clearPreview();
  }, [emitChange, detectTrigger, clearPreview]);

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
    [triggerRef, dismissPicker, emitChange],
  );

  // Вставить ссылку (по умолчанию: Enter / клик по строке)
  const insertItem = useCallback((item) => doInsert(tokenFor(item, false)), [doInsert, tokenFor]);

  // Вставить с содержимым (кнопка в дропдауне)
  const insertItemWithContent = useCallback((item) => doInsert(tokenFor(item, true)), [doInsert, tokenFor]);

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
          moveSelection(1);
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          moveSelection(-1);
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
          closePreview();
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
    [
      picker.open,
      picker.results,
      picker.idx,
      moveSelection,
      insertItem,
      dismissPicker,
      disabled,
      onSend,
      emitChange,
      closePreview,
    ],
  );

  const handleClick = useCallback(
    (e) => {
      const removeBtn = e.target.closest?.('.file-chip__remove');
      if (removeBtn) {
        e.preventDefault();
        const chip = removeBtn.closest('.file-chip');
        chip?.remove();
        emitChange();
        closePreview();
        return;
      }
      const chip = e.target.closest?.('.file-chip');
      if (chip) {
        e.preventDefault();
        openFromChip(chip);
      }
    },
    [emitChange, closePreview, openFromChip],
  );

  const isEmpty = !value || value.length === 0;

  return (
    <>
      <RichTextEditor
        ref={editorRef}
        isEmpty={isEmpty}
        disabled={disabled}
        placeholder={placeholder}
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

      {preview && <FileChipPreview preview={preview} onClose={closePreview} onToggleRef={toggleRef} />}
    </>
  );
});

export default ChipEditor;
