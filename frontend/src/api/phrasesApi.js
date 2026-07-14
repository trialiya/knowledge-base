// ─── Phrases API ────────────────────────────────────────────────────────────
// Тонкая обёртка над REST-эндпоинтами библиотеки фраз.
// Публичные ручки (для чата) — /api/phrases; админские — /api/admin/phrases.
// Используется и chatPanel (Phrases.jsx), и settingsPanel (PhrasesSettings.jsx) —
// поэтому живёт в общем src/api, а не в одной из панелей.

import { request, json } from './client';

// ── Публичные (чат) ──────────────────────────────────────────────────────────

/** Включённые фразы, отсортированные по категории и позиции. */
export const fetchPhrases = () => request('/api/phrases');

/** Переключить «избранное» прямо из блока фраз в чате. */
export const toggleFavorite = (id, value) => request(`/api/phrases/${id}/favorite?value=${value}`, { method: 'PATCH' });

// ── Админские (настройки) ────────────────────────────────────────────────────

/** Все фразы, включая выключенные. q — быстрый поиск по label. */
export const fetchAllPhrases = (q = '') => request(`/api/admin/phrases${q ? `?q=${encodeURIComponent(q)}` : ''}`);

export const createPhrase = (body) => request('/api/admin/phrases', { method: 'POST', ...json(body) });

export const updatePhrase = (id, body) => request(`/api/admin/phrases/${id}`, { method: 'PUT', ...json(body) });

export const deletePhrase = (id) => request(`/api/admin/phrases/${id}`, { method: 'DELETE' });

export const adminToggleFavorite = (id, value) =>
  request(`/api/admin/phrases/${id}/favorite?value=${value}`, { method: 'PATCH' });

export const adminToggleEnabled = (id, value) =>
  request(`/api/admin/phrases/${id}/enabled?value=${value}`, { method: 'PATCH' });

export const movePhrase = (id, position) =>
  request(`/api/admin/phrases/${id}/move`, { method: 'PATCH', ...json({ position }) });
