/**
 * Фикстуры ответа GET /api/settings/ai-config (SettingsController.AiConfigResponse).
 *
 * На этом снимке строятся три группы «Настроек» — «Модели», «Поиск» и
 * «Инструменты»: каждая берёт свою ветку одного и того же ответа. Данные
 * синтетические, но структура один в один совпадает с ответом бэкенда на
 * дефолтном application.yaml — иначе кейс проверял бы не то, что видит
 * пользователь.
 */

/**
 * Дефолт из application.yaml на профиле h2: семантика выключена, правка файлов
 * и MCP выключены, режимов три. Именно эта комбинация интересна визуально —
 * бейджи «отключён»/«нет», список подключений MCP без состояний и полный список
 * инструментов саб-агента, который не влезает в одну строку.
 */
export const defaultAiConfig = {
  chat: {
    defaultModel: { id: 'gpt-4o-mini', label: 'GPT-4o mini', weak: true, ownEndpoint: false },
    models: [],
    options: {
      maxTokens: 30000,
      temperature: 0.1,
      topP: 0.8,
      requestTimeoutSeconds: 600,
      retryMaxAttempts: 2,
      sseTimeoutSeconds: 1800,
    },
  },
  embedding: {
    model: 'bge-m3',
    reindexBatchSize: 50,
    chunker: { maxTokens: 512, overlapTokens: 64 },
    cache: { enabled: true, ttlDays: 30 },
  },
  searchCodebase: {
    enabled: true,
    modelId: 'gpt-4o-mini',
    maxTokens: 12000,
    maxIterations: 30,
    allowedTools: [
      'findDocumentsByName',
      'getDocument',
      'getDocumentOutline',
      'getDocumentSection',
      'getFileContent',
      'getFileOutline',
      'getFileTree',
      'getTreeSkeleton',
      'grepContent',
      'searchDocuments',
      'searchFiles',
    ],
  },
  summarize: {
    tokenThreshold: 30000,
    messageCountThreshold: 50,
    overlapMessages: 30,
    overlapUserMessages: 5,
    summaryCollapseThreshold: 5,
    charsPerToken: 4,
  },
  search: {
    keyword: { limit: 20 },
    semantic: { enabled: false, threshold: 0.2, limit: 20 },
    hybrid: { keywordWeight: 0.4, semanticWeight: 0.6, threshold: 0.2, limit: 20 },
  },
  tools: {
    modes: [
      { id: 'analytic', label: 'Аналитик' },
      { id: 'developer', label: 'Разработчик' },
      { id: 'tester', label: 'Тестировщик' },
    ],
    git: { editEnabled: false, editActive: false },
    // Соединения настроены, но MCP выключен: состояния у них нет и быть не может
    // (к серверам никто не ходит), поэтому в чипах только имя и транспорт.
    mcp: {
      enabled: false,
      active: false,
      retryIntervalMs: 60000,
      connections: [{ name: 'atlassian', transport: 'streamable-http', status: 'PENDING', toolCount: 0 }],
    },
    uploads: { maxFileSizeBytes: 1048576, maxRequestSizeBytes: 2097152 },
  },
  // kb.script.* с дефолтами из application.yaml: инструмент выключен, поэтому
  // в группе «Скрипты» видны пояснение про выключенный runScript и
  // заблокированная кнопка запуска.
  script: {
    enabled: false,
    active: false,
    editEnabled: true,
    editActive: false,
    // Бэкенд считает attachmentRun как `attachment-run && enabled`: при выключенной
    // песочнице «включено по умолчанию» ничего не значит, и панель показывает «нет».
    attachmentRun: false,
    attachmentEdit: false,
    schedules: 0,
    timeoutSeconds: 10,
    maxTimeoutSeconds: 30,
    cancelPollMillis: 50,
    limits: {
      maxFilesRead: 2000,
      maxBytesRead: 33554432,
      maxCalls: 2000,
      maxLogChars: 20000,
      maxResultChars: 20000,
      maxEditedFiles: 20,
      maxEditedBytes: 262144,
    },
  },
};

/**
 * Вариант «всё включено»: правка файлов разрешена, но дерево read-only — ровно
 * тот случай, ради которого в панели две строки вместо одной, и единственный,
 * в котором показывается пояснение tools.git.readOnlyNote. Плюс подключённые
 * MCP-серверы: имя и транспорт есть, токенов нет и быть не может.
 */
export const editEnabledButReadOnlyTree = {
  ...defaultAiConfig,
  tools: {
    ...defaultAiConfig.tools,
    git: { editEnabled: true, editActive: false },
    mcp: {
      enabled: true,
      active: true,
      retryIntervalMs: 60000,
      // Один сервер отвечает, второй лежит — состояние, ради которого приложение
      // больше не падает при старте: инструменты первого выданы, второй ждёт
      // повтора (см. McpToolRegistry).
      connections: [
        { name: 'atlassian', transport: 'streamable-http', status: 'UP', toolCount: 12 },
        { name: 'filesystem', transport: 'stdio', status: 'DOWN', toolCount: 0 },
      ],
    },
  },
};

/**
 * MCP включён в конфиге, но инструменты MCP выключены ключом самого стартера
 * (spring.ai.mcp.client.toolcallback.enabled): соединение настроено, а опрашивать
 * его некому. Единственное состояние, в котором видно пояснение
 * tools.mcp.inactiveNote — и в котором строка «Инструменты MCP» говорит
 * «включён», а состояния у подключения нет.
 */
export const mcpEnabledButToolCallbacksOff = {
  ...defaultAiConfig,
  tools: {
    ...defaultAiConfig.tools,
    mcp: { ...defaultAiConfig.tools.mcp, enabled: true, active: false },
  },
};

/**
 * Список моделей, в котором weak размечен по-разному: пилюля «weak» стоит у
 * одной строки и отсутствует у другой. Третья строка — модель со своим
 * подключением (kb.chat.models[].base-url + api-key): наружу отдаётся только
 * флаг ownEndpoint, ни адреса, ни токена в снимке нет. На defaultAiConfig
 * секции «Доступные модели» нет вовсе — kb.chat.models пуст.
 */
export const strongAndWeakModels = {
  ...defaultAiConfig,
  chat: {
    ...defaultAiConfig.chat,
    defaultModel: { id: 'strong-model', label: 'Strong', weak: false, ownEndpoint: false },
    models: [
      { id: 'strong-model', label: 'Strong', weak: false, ownEndpoint: false },
      { id: 'weak-model', label: 'Weak', weak: true, ownEndpoint: false },
      { id: 'remote-model', label: 'Remote', weak: false, ownEndpoint: true },
    ],
  },
};

/**
 * runScript включён (KB_SCRIPT_ENABLED=true) — единственное состояние, в
 * котором пробный запуск действительно работает.
 */
export const scriptEnabled = {
  ...defaultAiConfig,
  script: {
    ...defaultAiConfig.script,
    enabled: true,
    active: true,
    attachmentRun: true,
    schedules: 2,
  },
};

/**
 * Что репозиторий объявил в манифесте (`GET /api/settings/script/saved`) — список,
 * из которого выбирают в стенде сохранённых скриптов. Две записи не для объёма:
 * у одной объявлены аргументы и свой бюджет, у другой нет ни того, ни другого, и
 * форма под выбором выглядит в этих двух случаях по-разному.
 */
export const savedScripts = {
  project: 'kb',
  label: 'Knowledge Base',
  scripts: [
    {
      name: 'locale-diff',
      desc: 'Чего не хватает в одной локали против другой',
      file: '.kb/scripts/locale-diff.js',
      write: false,
      timeoutSeconds: 20,
      params: [
        { name: 'area', desc: 'Раздел локали: chat, settings, common', type: 'string', required: true, defaultValue: null },
        { name: 'limit', desc: 'Сколько ключей показать', type: 'number', required: false, defaultValue: 50 },
      ],
    },
    {
      name: 'todo-index',
      desc: 'Пересобрать docs/todo/.index.md по файлам каталога',
      file: 'docs/todo/build-index.js',
      write: true,
      timeoutSeconds: null,
      params: [],
    },
  ],
};

/**
 * Что развёртка запускает сама (`GET /api/settings/script/schedules`): одно
 * расписание уже отработало, второе с запуска сервера ещё ни разу — это и есть
 * две строки, которые панель показывает по-разному.
 */
export const scriptSchedules = [
  {
    name: 'nightly-locale-diff',
    script: 'locale-diff',
    project: 'kb',
    cron: '0 0 3 * * *',
    lastRun: { at: '2026-09-20T03:00:12Z', ok: true, error: null, elapsedMs: 1840 },
  },
  {
    name: 'weekly-todo-index',
    script: 'todo-index',
    project: 'kb',
    cron: '0 0 4 * * 1',
    lastRun: null,
  },
];
