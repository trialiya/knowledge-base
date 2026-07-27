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
    defaultModel: { id: 'gpt-4o-mini', label: 'GPT-4o mini' },
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
