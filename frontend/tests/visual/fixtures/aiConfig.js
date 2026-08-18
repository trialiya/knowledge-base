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
 * бейджи «отключён»/«нет», пустой список подключений MCP и полный список
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
    mcp: { enabled: false, connections: [] },
    uploads: { maxFileSizeBytes: 1048576, maxRequestSizeBytes: 2097152 },
  },
  // kb.script.* с дефолтами из application.yaml: инструмент выключен, поэтому
  // в группе «Скрипты» видны пояснение про выключенный runScript и
  // заблокированная кнопка запуска.
  script: {
    enabled: false,
    editEnabled: true,
    editActive: false,
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
    denyGlobs: [],
    allowGlobs: [],
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
      connections: [
        { name: 'atlassian', transport: 'streamable-http' },
        { name: 'filesystem', transport: 'stdio' },
      ],
    },
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
  script: { ...defaultAiConfig.script, enabled: true },
};
