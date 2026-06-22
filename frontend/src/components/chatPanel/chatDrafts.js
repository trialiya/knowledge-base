// ─── Per-chat composer drafts ────────────────────────────────────────────────
// Неотправленный текст поля ввода хранится по chatId, чтобы переключение чатов не
// теряло черновик (и он переживал перезагрузку). Источник — localStorage, ключ
// STORAGE_KEY_CHAT_DRAFTS. Здесь только чистые операции над картой { chatId: text }
// и её сериализация; тайминги записи (debounce) держит вызывающий компонент.

import { STORAGE_KEY_CHAT_DRAFTS } from '../../constants/storage';

/** Прочитать карту черновиков из localStorage. Любой сбой → пустая карта. */
export function loadDrafts() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_CHAT_DRAFTS);
    const obj = raw ? JSON.parse(raw) : null;
    return obj && typeof obj === 'object' && !Array.isArray(obj) ? obj : {};
  } catch {
    return {};
  }
}

/** Сохранить карту черновиков. Ошибки квоты глотаем — черновик не критичен. */
export function saveDrafts(map) {
  try {
    localStorage.setItem(STORAGE_KEY_CHAT_DRAFTS, JSON.stringify(map));
  } catch {
    /* ignore quota / serialization errors */
  }
}

/** Черновик конкретного чата (пустая строка, если его нет). */
export function getDraft(map, id) {
  return (id && map[id]) || '';
}

/**
 * Записать/удалить черновик чата в карте (мутирует map на месте).
 * Пустой/пробельный текст удаляет запись, чтобы карта не копила мусор.
 */
export function setDraft(map, id, text) {
  if (!id) return;
  if (text && text.trim()) map[id] = text;
  else delete map[id];
}
