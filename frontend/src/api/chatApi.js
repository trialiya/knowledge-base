// ─── Chat API ────────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/chats/* эндпоинтов.
// Поток событий (GET /events) живёт отдельно в chatEvents.js — там нужен прямой
// доступ к response.body для побайтового SSE-чтения.

import { request, requestRaw, json } from './client';

const enc = (id) => encodeURIComponent(id);

// Тело запроса, отправляющего сообщение в чат, — одно на POST /runs и на постановку в
// очередь идущего прогона (StartRunRequest на бэке): выбор едет вместе с сообщением, а не
// параметрами адреса, который целиком попадает в логи вместе с текстом вопроса.
const runBody = (text, contextItems, { model, mode, project, clientMsgId, retry = false }) => ({
  text: text || null,
  contextItems: contextItems || [],
  model: model || null,
  mode: mode || null,
  project: project || null,
  clientMsgId: clientMsgId || null,
  retry,
});

const chatApi = {
  /** Доступные модели и дефолтная: { defaultModel, models }. */
  getModels: () => request('/api/chats/models'),

  /** Готовые режимы ассистента: [{ id, label }]. «Без режима» на фронте — синтетический пункт. */
  getModes: () => request('/api/chats/modes'),

  /**
   * Проекты (репозитории), между которыми можно выбирать:
   * { defaultProject, projects: [{ id, label }] }.
   *
   * Дефолтный назван явно, а не подразумевается первым: чат хранит project=null,
   * пока пользователь ничего не выбирал, и селектору нужно знать, что подсветить.
   */
  getProjects: () => request('/api/chats/projects'),

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
   * Счёт токенов за весь чат: { baseContextTokens, spent, subagentRuns, subagentSpent }.
   *
   * Отдельным запросом, а не полем страницы: страница — это хвост разговора (два десятка
   * сообщений), и итог по ней был бы итогом по хвосту. «Сколько занято сейчас» фронт по-прежнему
   * считает по ленте сам — там нужен последний замер, и хвоста для него достаточно.
   */
  getUsage: (id) => request(`/api/chats/${enc(id)}/usage`),

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
   * Запустить генерацию ответа как фоновую задачу. Возвращает { runId, messageId }.
   * Сам ответ приходит не здесь, а потоком событий (chatEvents.js).
   * clientMsgId — чтобы не задвоить свой оптимистичный пузырь при получении эха.
   *
   * messageId — id уже сохранённого сообщения пользователя: бэк пишет его до обращения
   * к модели. Отправившая вкладка гасит своё эхо USER_MESSAGE по clientMsgId, поэтому id
   * она узнаёт только отсюда — без него якорь поиска по чату появился бы у пузыря лишь
   * после перезагрузки страницы.
   *
   * contextItems — что приложено к этому сообщению: [{ kind, ref }]. Бэк проверяет ссылки,
   * сам проставляет подписи и кладёт результат в meta того же ряда.
   *
   * retry — повтор упавшего прогона: текста не передаём, ходом остаётся уже сохранённый
   * вопрос со своим контекстом. Если модель успела начать ответ, бэк отвечает 422.
   */
  startRun: (id, text, { model, mode, project, clientMsgId, retry, contextItems } = {}) =>
    request(`/api/chats/${enc(id)}/runs`, {
      method: 'POST',
      ...json(runBody(text, contextItems, { model, mode, project, clientMsgId, retry })),
    }),

  /**
   * Отправить сообщение, не дожидаясь конца текущего ответа: оно встаёт в очередь идущего
   * прогона (202, без тела). В историю чата попадёт позже — между вызовами инструментов, а
   * если такого места не случится, то в конце прогона (см. PendingMessageService).
   *
   * Ни runId, ни id сообщения не возвращаются: ряда истории у него ещё нет. О приёме вкладки
   * узнают событием MESSAGE_QUEUED, о доставке — обычным USER_MESSAGE.
   *
   * `409` — этот прогон уже не генерирует (кончился, пока набирали): вызывающий повторяет
   * обычным startRun.
   */
  queueMessage: (id, runId, text, { model, mode, project, clientMsgId, contextItems } = {}) =>
    request(`/api/chats/${enc(id)}/runs/${enc(runId)}/messages`, {
      method: 'POST',
      ...json(runBody(text, contextItems, { model, mode, project, clientMsgId })),
    }),

  /**
   * Сжать контекст чата (команда `/compact`). Возвращает { runId, messageId }: сам раунд идёт
   * в фоне, исход приезжает событиями COMPACT_DONE/COMPACT_ERROR. Пока он идёт, чат занят так
   * же, как на генерации, — вопрос в него получит 409.
   *
   * text — сообщение целиком (с самим `/compact`), сохраняется как обычная реплика и остаётся
   * видно в истории — в отличие от instructions (хвост команды), которое в сжатие не входит,
   * только в фокус для него. clientMsgId — как у startRun: гасит своё эхо USER_MESSAGE.
   */
  compact: (id, text, instructions, clientMsgId) =>
    request(`/api/chats/${enc(id)}/compact`, {
      method: 'POST',
      ...json({ text, instructions: instructions || null, clientMsgId: clientMsgId || null }),
    }),

  /**
   * Детали одного сжатия по id его плашки: { messageId, messages, summaryChars, createdAt,
   * summary }. Отдельным запросом, а не полем страницы истории: сводка бывает в десятки
   * килобайт, а открывают её изредка и по одной.
   */
  getCompactDetail: (chatId, messageId) => {
    const params = new URLSearchParams({ messageId: String(messageId) });
    return request(`/api/chats/${enc(chatId)}/compact?${params}`);
  },

  /** Остановить прогон. Ошибки — только в консоль. */
  stopRun: (id, runId) =>
    requestRaw(`/api/chats/${enc(id)}/runs/${enc(runId)}/stop`, { method: 'POST' }).catch((e) =>
      console.error('stopRun error:', e),
    ),

  /**
   * Откатить файловые правки последнего ответа: файлы возвращаются к состоянию до него, а в
   * истории остаётся ряд об этом. Репозиторий не передаётся: его называет история самого чата —
   * селектор проекта могли переключить уже после ответа — { id, createdAt, event: { project, paths } }, из которого
   * лента рисует плашку, а модель узнаёт об откате на следующем ходу.
   *
   * Отказ приезжает текстом (как у git-команд): «файл изменился после ответа», «правки скрипта
   * так не откатываются» — это и есть ответ пользователю, поэтому тело разбирается, а не
   * сводится к коду состояния.
   */
  revertFiles: async (id) => {
    const res = await requestRaw(`/api/chats/${enc(id)}/revert-files`, { method: 'POST' });
    const text = await res.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = null;
      }
    }
    if (res.ok) return body;
    const err = new Error(body?.message || `HTTP ${res.status}`);
    err.status = res.status;
    err.reason = body?.message ?? null;
    throw err;
  },

  /** runId активного прогона чата (или {}). Для восстановления состояния. */
  getActiveRun: (id) => request(`/api/chats/${enc(id)}/runs/active`),
};

export default chatApi;
