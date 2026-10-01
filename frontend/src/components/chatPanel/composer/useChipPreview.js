// ─── File-chip preview (полноэкранная модалка содержимого) ───────────────────
// Хук владеет состоянием превью чипа: открытие по клику и переключение чипа
// между режимами «содержимое» ⇄ «только путь». Содержимое читает сама модалка
// (FileChipPreview → useFileContent).
// Превью только у файловых чипов. Мутацию DOM самого чипа делает toggleRef, после
// чего зовёт onAfterToggle (обычно emitChange редактора), чтобы значение обновилось.

import { useState, useCallback } from 'react';
import { makeToken, makeRefToken, parseToken, baseName, chipLabel } from './fileChips';

export default function useChipPreview({ chatId, project, onAfterToggle }) {
  // { path, from, to, project, refOnly, rect, chipEl } | null
  const [preview, setPreview] = useState(null);

  // Закрываем превью при переключении чата — в рендере, а не эффектом: иначе
  // модалка успевает мигнуть содержимым файла из предыдущего чата.
  const [prevChatId, setPrevChatId] = useState(chatId);
  if (prevChatId !== chatId) {
    setPrevChatId(chatId);
    setPreview(null);
  }

  const close = useCallback(() => setPreview(null), []);

  // Мягкое закрытие: только если что-то открыто (для пути input, без лишних ре-рендеров).
  const clear = useCallback(() => setPreview((pv) => (pv ? null : pv)), []);

  const openFromChip = useCallback((chip) => {
    const token = chip.dataset.token;
    // Превью есть только у файловых чипов: у doc- и commit-чипов нечего показывать
    // инлайн — их содержимое уже целиком в самом токене.
    const parsed = parseToken(token);
    if (!parsed) return;
    setPreview({ ...parsed, rect: chip.getBoundingClientRect(), chipEl: chip });
  }, []);

  // Переключение чипа между режимами «содержимое» и «только путь».
  const toggleRef = useCallback(() => {
    setPreview((pv) => {
      if (!pv) return pv;
      const { chipEl, path, from, to, refOnly } = pv;
      const newRefOnly = !refOnly;
      // Проект переезжает в новый токен: смена режима — это про содержимое, а не
      // про репозиторий, и потерять его здесь значило бы отдать чип проекту чата.
      const newToken = newRefOnly ? makeRefToken(path, pv.project) : makeToken(path, { from, to, project: pv.project });
      chipEl.dataset.token = newToken;
      const label = chipEl.querySelector('.file-chip__label');
      const range = from != null ? `:${from}-${to}` : '';
      if (label) label.textContent = chipLabel(pv.project, project, baseName(path) + range);
      const icon = chipEl.querySelector('.file-chip__icon');
      if (icon) icon.textContent = newRefOnly ? '📎' : '📄';
      if (newRefOnly) chipEl.classList.add('file-chip--ref');
      else chipEl.classList.remove('file-chip--ref');
      // emitChange внутри setPreview вызвать нельзя, откладываем.
      return { ...pv, refOnly: newRefOnly };
    });
    // onAfterToggle здесь — после обновления DOM чипа.
    setTimeout(() => onAfterToggle?.(), 0);
  }, [onAfterToggle, project]);

  return { preview, openFromChip, toggleRef, close, clear };
}
