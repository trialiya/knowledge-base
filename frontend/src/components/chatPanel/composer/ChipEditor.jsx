import { useRef, useEffect, useCallback, useImperativeHandle } from 'react';
import {
  serialize,
  breakLineAtCaret,
  renderValue,
  placeCaretEnd,
  normalizeTrailingSentinel,
  relabelChips,
  insertPlainText,
  getCaretOffset,
  placeCaretAtOffset,
} from './fileChipEditorDom';
import FilePickerDropdown from './FilePickerDropdown';
import SlashMenuDropdown from './SlashMenuDropdown';
import FileChipPreview from './FileChipPreview';
import RichTextEditor from './RichTextEditor';
import useChipPicker from './useChipPicker';
import useChipPreview from './useChipPreview';
import useCommandHighlight from './useCommandHighlight';
import useSlashMenu from './useSlashMenu';

// ── Компонент ─────────────────────────────────────────────────────────────────
// Композер чата: rich-text редактор с чипами файлов и документов (/file, /doc).
// Владеет переходами value ⇄ DOM, вводом, клавиатурой, вставкой plain-text и
// вставкой чипа в DOM; сам div рендерит RichTextEditor. Логику выпадающего
// списка и превью чипа держат хуки useChipPicker / useChipPreview.

// commandState — условия, по которым список со слэша гасит невыполнимую сейчас
// команду (см. chatCommandBlock). Без него — пустые условия: список назовёт
// невыполнимым то, что могло бы пройти, но не наоборот.
function ChipEditor({ value, onChange, onSend, disabled, placeholder, chatId, project, commandState = {}, ref }) {
  const editorRef = useRef(null);
  const internalRef = useRef(value);
  // Идёт программная вставка (handlePaste): её промежуточные input-события
  // пропускаем, см. handleInput.
  const pastingRef = useRef(false);

  useImperativeHandle(ref, () => ({
    focus: () => editorRef.current?.focus(),
    // Фокус на contenteditable ставит каретку в начало содержимого. Тому, кто
    // только что положил в поле текст (вставка фразы), нужен конец — иначе
    // дописывать приходится, сначала промотав курсор через всю фразу.
    focusEnd: () => {
      const root = editorRef.current;
      if (!root) return;
      root.focus();
      placeCaretEnd(root);
    },
  }));

  useEffect(() => {
    if (value === internalRef.current) return;
    internalRef.current = value;
    const root = editorRef.current;
    if (!root) return;
    renderValue(root, value, project);
    if (document.activeElement === root) placeCaretEnd(root);
    // project — не повод перерисовать поле (для этого relabelChips ниже), но
    // рисовать надо уже с ним: подписи чипов зависят от проекта чата.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  // Смена проекта чата меняет только подписи чипов: свои теряют имя проекта, чужие
  // получают. Правим их на месте — перерисовка поля стоила бы стека отмены и каретки.
  useEffect(() => {
    if (editorRef.current) relabelChips(editorRef.current, project);
  }, [project]);

  useEffect(() => {
    if (editorRef.current) renderValue(editorRef.current, value, project);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Подсветка команды чату (`/compact`) — после эффектов, которые перерисовывают
  // поле: её Range'и держат конкретные текстовые узлы и пережить их замену не
  // могут, поэтому считаются по уже нарисованному DOM.
  useCommandHighlight(editorRef, value);

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

  const { picker, detectTrigger, dismissPicker, moveSelection, insertItem, insertItemWithContent } = useChipPicker(
    project,
    editorRef,
    emitChange,
  );

  // Список со слэша считается по набранному, а владение отдаётся поиску чипа,
  // как только тот узнал полный триггер: два списка у одной каретки перекрыли бы
  // друг друга. Стёрли до неполного — список вернулся.
  const slash = useSlashMenu(value);
  const slashOpen = slash.open && !picker.open;

  const {
    preview,
    openFromChip,
    toggleRef,
    close: closePreview,
    clear: clearPreview,
  } = useChipPreview({
    chatId,
    project,
    onAfterToggle: emitChange,
  });

  const handleInput = useCallback(() => {
    // Вставка идёт несколькими execCommand подряд, и каждая шлёт свой input.
    // Обрабатывать их по одному нельзя: normalizeTrailingSentinel внутри
    // emitChange дописывала бы sentinel-<br> в середину незаконченной вставки, а
    // следующая команда печатала бы уже мимо него — многострочный текст приезжал
    // с лишним переносом в конце. Ждём конца вставки, handlePaste позовёт сам.
    if (pastingRef.current) return;
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

  // Выбранное в списке со слэша дописывается обычным текстом: команда не чип, в
  // токен ей разворачиваться не во что, а дальше её ведёт подсветка. Поле в этот
  // момент состоит из одного набранного префикса (slashMenuItems), поэтому
  // заменяем его целиком — execCommand поверх выделения, чтобы вставка попала в
  // нативный стек отмены.
  const insertSlashItem = useCallback(
    (item) => {
      const root = editorRef.current;
      if (!root) return;
      root.focus();
      const sel = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(root);
      sel.removeAllRanges();
      sel.addRange(range);
      document.execCommand('insertText', false, `${item.trigger} `);
      emitChange();
      // Триггер чипа дописан целиком — поиск открывается сразу, без лишнего символа.
      detectTrigger();
    },
    [emitChange, detectTrigger],
  );

  // Сбрасываем форматирование при вставке — вставляем только plain text.
  // insertPlainText делает это через execCommand, поэтому вставка попадает в
  // нативный стек отмены и Ctrl+Z её отменяет — целиком, одним шагом.
  //
  // НЕ ВОЗВРАЩАТЬ СЮДА renderValue НА ОСНОВНОМ ПУТИ. Она пересобирает поле
  // через `textContent = ''`, а браузер выбрасывает стек отмены, как только
  // скрипт заменяет узлы, на которые ссылаются его шаги. Ровно так уже вышло:
  // #130 сделал вставку отменяемой, #147 добавил здесь пересборку ради плоского
  // DOM — и молча отменил цель #130, оставив комментарий, что Ctrl+Z работает.
  // Плоский DOM теперь обеспечивает сама вставка, пересобирать нечего.
  //
  // Резервный путь (браузер без 'insertLineBreak', то есть Firefox на
  // многострочном тексте): вставляем весь текст одной командой и пересобираем
  // DOM в плоский вид, потому что на многострочном тексте execCommand('insertText')
  // заворачивает строки в блочные <div>, а normalizeTrailingSentinel,
  // Shift+Enter-обработчик и placeCaretAtOffset смотрят только на прямых детей
  // root. Смещение каретки снимаем в терминах value ДО перерисовки (пока виден
  // "грязный" DOM с <div>), рисуем плоский DOM через renderValue и ставим курсор
  // обратно по тому же смещению. Ценой отмены: renderValue пересобирает узлы
  // руками, а это стирает нативный стек — здесь иначе никак.
  const handlePaste = useCallback(
    (e) => {
      e.preventDefault();
      const text = (e.clipboardData || window.clipboardData).getData('text/plain');
      if (!text) return;
      const root = editorRef.current;
      if (!root) return;

      pastingRef.current = true;
      let inserted;
      try {
        inserted = insertPlainText(root, text);
      } finally {
        pastingRef.current = false;
      }
      if (inserted) {
        handleInput(); // один проход на готовом плоском DOM, вместо пропущенных
        return;
      }

      document.execCommand('insertText', false, text);
      const offset = getCaretOffset(root);
      const v = serialize(root);
      renderValue(root, v, project);
      if (offset != null) placeCaretAtOffset(root, offset);
      internalRef.current = v;
      onChange(v);
    },
    [onChange, handleInput, project],
  );

  const handleKeyDown = useCallback(
    (e) => {
      if (slashOpen) {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          slash.move(e.key === 'ArrowDown' ? 1 : -1);
          return;
        }
        if (e.key === 'Enter') {
          e.preventDefault();
          insertSlashItem(slash.items[slash.idx]);
          return;
        }
        if (e.key === 'Escape') {
          e.preventDefault();
          slash.dismiss();
          return;
        }
      }

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
        breakLineAtCaret(editorRef.current);
        emitChange();
      }
    },
    [
      slashOpen,
      slash,
      insertSlashItem,
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
        onBlur={() =>
          setTimeout(() => {
            dismissPicker();
            slash.dismiss();
          }, 120)
        }
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

      {slashOpen && (
        <SlashMenuDropdown
          items={slash.items}
          query={value}
          selectedIdx={slash.idx}
          commandState={commandState}
          onSelect={insertSlashItem}
          onDismiss={slash.dismiss}
        />
      )}

      {preview && (
        <FileChipPreview preview={preview} project={project} onClose={closePreview} onToggleRef={toggleRef} />
      )}
    </>
  );
}

export default ChipEditor;
