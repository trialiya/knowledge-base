// ─── Chat API ────────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/chats/* эндпоинтов.
// Поток событий (GET /events) живёт отдельно в chatEvents.js — там нужен прямой
// доступ к response.body для побайтового SSE-чтения.

import { request, requestRaw } from './client';

const enc = (id) => encodeURIComponent(id);

const chatApi = {
  /** Доступные модели и дефолтная: { defaultModel, models }. */
  getModels: () => request('/api/chats/models'),

  /** Готовые режимы ассистента: [{ id, label }]. «Без режима» на фронте — синтетический пункт. */
  getModes: () => request('/api/chats/modes'),

  /** Список всех чатов. */
  listChats: () => request('/api/chats'),

  /** Метаданные чата без сообщений (topic, model, createdAt). */
  getChatMeta: (id) => request(`/api/chats/${enc(id)}?includeMessages=false`),

  /**
   * Страница сообщений. Без cursor — последняя страница (limit последних).
   * cursor: { createdAt, id } — берём сообщения старше этой точки.
   */
  getMessages: (id, limit, cursor) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) {
      params.set('beforeCreatedAt', cursor.createdAt);
      params.set('beforeId', cursor.id);
    }
    return request(`/api/chats/${enc(id)}/messages?${params}`);
  },

  /**
   * Поиск сообщений внутри одного чата — для локального find-бара (Ctrl+F).
   * Возвращает совпадения в хронологическом порядке: [{ id, createdAt }].
   */
  searchMessages: (id, q, signal) =>
    request(`/api/chats/${enc(id)}/messages/search?${new URLSearchParams({ q })}`, signal ? { signal } : undefined),

  /**
   * Поиск чатов текущего пользователя по названию и/или содержимому сообщений (лупа над списком).
   * Возвращает [{ conversationId, topic, updatedAt, titleMatched, messageMatchCount, snippet }].
   */
  searchChats: (q, limit = 20, signal) => {
    const params = new URLSearchParams({ q, limit: String(limit) });
    return request(`/api/chats/search?${params}`, signal ? { signal } : undefined);
  },

  /** Полные детали одного вызова инструмента — точечно по протокольному id вызова. */
  getToolCallDetails: (chatId, callId) => {
    const params = new URLSearchParams({ callId });
    return request(`/api/chats/${enc(chatId)}/tool-calls?${params}`);
  },

  /** Количество вложений активного чата (для бейджа). */
  getAttachmentCount: (id) => request(`/api/chats/${enc(id)}/attachments/count`),

  /** Список вложений чата. */
  listAttachments: (id) => request(`/api/chats/${enc(id)}/attachments`),

  /**
   * Переименовать чат. Тело — plain string (контракт бэка),
   * Content-Type: application/json выставлен намеренно (существующий контракт).
   */
  renameChat: (id, title) =>
    request(`/api/chats/${enc(id)}/topic`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: title,
    }),

  /**
   * Удалить чат. Возвращает сырой Response — вызывающий код обязан проверить
   * res.ok перед тем, как убирать чат из UI (см. ChatWindow.confirmDeleteChat).
   */
  deleteChat: (id) => requestRaw(`/api/chats/${enc(id)}`, { method: 'DELETE' }),

  /** Сменить модель чата. Тело — plain string. */
  updateModel: (id, modelId) =>
    request(`/api/chats/${enc(id)}/model`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: modelId,
    }),

  /** Сменить режим чата. Тело — plain string ('' → без режима). */
  updateMode: (id, modeId) =>
    request(`/api/chats/${enc(id)}/mode`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: modeId || '',
    }),

  /**
   * Запустить генерацию ответа как фоновую задачу. Возвращает { runId }.
   * Сам ответ приходит не здесь, а потоком событий (chatEvents.js).
   * clientMsgId — чтобы не задвоить свой оптимистичный пузырь при получении эха.
   */
  startRun: (id, text, { model, mode, clientMsgId } = {}) => {
    const params = new URLSearchParams();
    if (model) params.set('model', model);
    if (mode) params.set('mode', mode);
    if (clientMsgId) params.set('clientMsgId', clientMsgId);
    const qs = params.toString();
    return request(`/api/chats/${enc(id)}/runs${qs ? `?${qs}` : ''}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: text,
    });
  },

  /** Остановить прогон. Ошибки — только в консоль. */
  stopRun: (id, runId) =>
    requestRaw(`/api/chats/${enc(id)}/runs/${enc(runId)}/stop`, { method: 'POST' }).catch((e) =>
      console.error('stopRun error:', e),
    ),

  /** runId активного прогона чата (или {}). Для восстановления состояния. */
  getActiveRun: (id) => request(`/api/chats/${enc(id)}/runs/active`),
};

export default chatApi;
