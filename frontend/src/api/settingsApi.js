import { request, json } from './client';

const settingsApi = {
  /** AI configuration snapshot: chat models, embedding, searchCodebase, summarize, search, tools, script. */
  getAiConfig: () => request('/api/settings/ai-config'),

  /**
   * Каталог инструментов, доступных модели прямо сейчас: имя, описание и аргументы
   * из JSON-схемы. Отдельно от ai-config — это описания и схемы, они на порядок
   * длиннее всего снимка конфигурации, который читают остальные группы.
   */
  getTools: () => request('/api/settings/tools'),

  /**
   * Пробный запуск скрипта из «Настроек → Скрипты». Тот же движок и те же
   * лимиты, что у инструмента runScript, но всегда read-only: kb.edit/kb.create
   * в песочницу не привязываются (см. ScriptTestController).
   *
   * Неуспех скрипта — не ошибка запроса: ответ 200 с заполненным `error`.
   * HTTP-ошибкой отвечает только сам эндпоинт — 409, когда kb.script.enabled=false.
   */
  runScript: (script, timeoutSeconds) =>
    request('/api/settings/script/run', { method: 'POST', ...json({ script, timeoutSeconds }) }),

  /** Server-side snapshot for the admin panel: app, database, git, documents, indexing queue. */
  getSystemInfo: () => request('/api/admin/system'),
};

export default settingsApi;
