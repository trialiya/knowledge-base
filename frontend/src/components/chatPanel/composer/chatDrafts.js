// ─── Per-chat composer drafts ────────────────────────────────────────────────
// Неотправленный текст поля ввода хранится по chatId, чтобы переключение чатов не
// теряло черновик (и он переживал перезагрузку). Источник — localStorage, ключ
// STORAGE_KEY_CHAT_DRAFTS. Здесь только чистые операции над картой { chatId: text }
// и её сериализация; тайминги записи (debounce) держит вызывающий компонент.

import { STORAGE_KEY_CHAT_DRAFTS, STORAGE_KEY_CHAT_STAGED } from '../../../constants/storage';

/** Прочитать карту из localStorage по ключу. Любой сбой → пустая карта. */
function loadMap(key) {
  try {
    const raw = localStorage.getItem(key);
    const obj = raw ? JSON.parse(raw) : null;
    return obj && typeof obj === 'object' && !Array.isArray(obj) ? obj : {};
  } catch {
    return {};
  }
}

/** Записать карту. Ошибки квоты глотаем — черновик не критичен. */
function saveMap(key, map) {
  try {
    localStorage.setItem(key, JSON.stringify(map));
  } catch {
    /* ignore quota / serialization errors */
  }
}

/** Прочитать карту черновиков из localStorage. Любой сбой → пустая карта. */
export function loadDrafts() {
  return loadMap(STORAGE_KEY_CHAT_DRAFTS);
}

/** Сохранить карту черновиков. Ошибки квоты глотаем — черновик не критичен. */
export function saveDrafts(map) {
  saveMap(STORAGE_KEY_CHAT_DRAFTS, map);
}

/**
 * Карта отложенных к отправке вложений: `{ [chatId]: [{ kind, ref, label }] }`.
 * Хранится отдельно от текста, но живёт по тем же правилам — это один черновик,
 * разнесённый по двум ключам, чтобы старый ключ остался строковым.
 */
export function loadStaged() {
  return loadMap(STORAGE_KEY_CHAT_STAGED);
}

export function saveStaged(map) {
  saveMap(STORAGE_KEY_CHAT_STAGED, map);
}

/**
 * Отложенные вложения чата. Пустой список — всегда одна и та же константа: иначе
 * каждый рендер отдавал бы композеру новый массив и зря дёргал его memo.
 */
const NO_STAGED = [];

export function getStaged(map, id) {
  const list = id ? map[id] : null;
  return Array.isArray(list) && list.length ? list : NO_STAGED;
}

/** Записать/удалить отложенные вложения чата (мутирует map на месте). */
export function setStaged(map, id, items) {
  if (!id) return;
  if (items && items.length) map[id] = items;
  else delete map[id];
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
