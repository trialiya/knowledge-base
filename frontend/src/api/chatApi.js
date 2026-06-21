// ─── Chat API ────────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/chats/* эндпоинтов.
// Поток событий (GET /events) живёт отдельно в chatEvents.js — там нужен прямой
// доступ к response.body для побайтового SSE-чтения.

import { request, requestRaw, json } from './client';

const enc = (id) => encodeURIComponent(id);

const chatApi = {
  /** Доступные модели и дефолтная: { defaultModel, models }. */
  getModels: () => request('/api/chats/models'),

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

  /** Полные детали одного вызова инструмента по runId + callIndex. */
  getToolCallDetails: (chatId, runId, callIndex) =>
    request(`/api/chats/${enc(chatId)}/tool-calls?runId=${enc(runId)}&callIndex=${callIndex}`),

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

  /** Удалить чат. Ошибки — только в консоль, UI не падает. */
  deleteChat: (id) =>
    requestRaw(`/api/chats/${enc(id)}`, { method: 'DELETE' }).catch((e) => console.error('deleteChat error:', e)),

  /** Сменить модель чата. Тело — plain string. */
  updateModel: (id, modelId) =>
    request(`/api/chats/${enc(id)}/model`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: modelId,
    }),

  /**
   * Создать Jira-чат. При ошибке бросает Error с телом ответа в message
   * (бэк отдаёт человекочитаемый текст), чтобы UI мог показать его пользователю.
   */
  createJiraChat: async (body) => {
    const res = await requestRaw('/api/chats/jira', { method: 'POST', ...json(body) });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      const err = new Error(text || `HTTP ${res.status}`);
      err.status = res.status;
      err.body = text;
      throw err;
    }
    return res.json();
  },

  /** Обновить вложения Jira-чата (pull из Jira). jiraUrl — тело запроса. */
  refreshJira: (id, jiraUrl) =>
    requestRaw(`/api/chats/${enc(id)}/refresh`, {
      method: 'POST',
      ...(jiraUrl !== undefined ? { headers: { 'Content-Type': 'application/json' }, body: jiraUrl } : {}),
    }),

  /**
   * Запустить генерацию ответа как фоновую задачу. Возвращает { runId }.
   * Сам ответ приходит не здесь, а потоком событий (chatEvents.js).
   * clientMsgId — чтобы не задвоить свой оптимистичный пузырь при получении эха.
   */
  startRun: (id, text, { model, clientMsgId } = {}) => {
    const params = new URLSearchParams();
    if (model) params.set('model', model);
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
