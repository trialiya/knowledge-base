// ─── File-chip preview (полноэкранная модалка содержимого) ───────────────────
// Хук владеет состоянием превью чипа: открытие по клику, ленивую загрузку
// содержимого и переключение чипа между режимами «содержимое» ⇄ «только путь».
// Doc-чипы превью не имеют. Мутацию DOM самого чипа делает toggleRef, после чего
// зовёт onAfterToggle (обычно emitChange редактора), чтобы значение обновилось.

import { useState, useEffect, useCallback } from 'react';
import {
  makeToken,
  makeRefToken,
  parseToken,
  parseDocToken,
  parseDocRefToken,
  baseName,
  fetchContent,
} from './fileChips';

export default function useChipPreview({ chatId, onAfterToggle }) {
  // { path, from, to, refOnly, rect, chipEl, data, loading, error } | null
  const [preview, setPreview] = useState(null);

  // Закрываем превью при переключении чата.
  useEffect(() => {
    setPreview(null);
  }, [chatId]);

  const close = useCallback(() => setPreview(null), []);

  // Мягкое закрытие: только если что-то открыто (для пути input, без лишних ре-рендеров).
  const clear = useCallback(() => setPreview((pv) => (pv ? null : pv)), []);

  const openFromChip = useCallback((chip) => {
    const token = chip.dataset.token;
    // У doc-чипов нет инлайн-превью.
    if (parseDocToken(token) || parseDocRefToken(token)) return;
    const parsed = parseToken(token);
    if (!parsed) return;
    const rect = chip.getBoundingClientRect();
    setPreview({ ...parsed, rect, chipEl: chip, loading: !parsed.refOnly, data: null, error: false });
    if (!parsed.refOnly) {
      fetchContent(parsed.path, parsed.from, parsed.to)
        .then((data) => setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, data } : pv)))
        .catch(() => setPreview((pv) => (pv && pv.path === parsed.path ? { ...pv, loading: false, error: true } : pv)));
    }
  }, []);

  // Переключение чипа между режимами «содержимое» и «только путь».
  const toggleRef = useCallback(() => {
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
      // emitChange внутри setPreview вызвать нельзя, откладываем.
      return { ...pv, refOnly: newRefOnly };
    });
    // onAfterToggle здесь — после обновления DOM чипа.
    setTimeout(() => onAfterToggle?.(), 0);
  }, [onAfterToggle]);

  return { preview, openFromChip, toggleRef, close, clear };
}
