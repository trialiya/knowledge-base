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

  /**
   * Что репозиторий объявил в манифесте прямо сейчас: имя, описание, файл, объявленные
   * аргументы. Список читается из рабочего дерева на каждый запрос, поэтому pull или
   * смена ветки меняют его без перезапуска сервера.
   */
  listSavedScripts: (project) =>
    request(`/api/settings/script/saved${project ? `?project=${encodeURIComponent(project)}` : ''}`),

  /**
   * Прогон сохранённого скрипта по имени — тот же движок и те же бюджеты, но код берётся
   * из репозитория, а не из поля формы. Всегда read-only, как и свободный прогон выше.
   */
  runSavedScript: (name, args, timeoutSeconds, project) =>
    request('/api/settings/script/run-saved', {
      method: 'POST',
      ...json({ name, args, timeoutSeconds, project }),
    }),

  /** Server-side snapshot for the admin panel: app, database, git, documents, indexing queue. */
  getSystemInfo: () => request('/api/admin/system'),
};

export default settingsApi;
