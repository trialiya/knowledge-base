/**
 * Фикстуры ответа GET /api/settings/tools (SettingsController.getTools →
 * ToolCatalogService.ToolInfo).
 *
 * На них строится каталог инструментов в группе «Настройки → Инструменты».
 * Данные синтетические, но структура и стиль описаний — как у настоящего
 * ответа: описания приходят из аннотаций @Tool и написаны для модели, поэтому
 * часть на русском, часть на английском, аргументы — из JSON-схемы.
 */

/**
 * Обычный набор профиля h2: только встроенные инструменты, правка файлов и
 * скрипты выключены. Три записи покрывают то, что различается визуально:
 * инструмент с обязательными аргументами, инструмент без аргументов и
 * инструмент с необязательными и перечислимыми значениями.
 */
export const builtinTools = [
  {
    name: 'copyAttachmentToDocument',
    description:
      'Скопировать вложение из текущего чата в документ базы знаний. Используй, когда пользователь хочет сохранить файл из чата в документ.',
    origin: 'builtin',
    params: [
      { name: 'attachmentId', type: 'integer', description: 'ID вложения из чата', required: true, values: [] },
      {
        name: 'targetDocumentId',
        type: 'integer',
        description: 'ID целевого документа в базе знаний',
        required: true,
        values: [],
      },
    ],
  },
  {
    name: 'getTreeSkeleton',
    description: 'List all knowledge base nodes (id, title, type, parentId) without content.',
    origin: 'builtin',
    params: [],
  },
  {
    name: 'searchDocuments',
    description: 'Search knowledge base documents by topic/keywords (hybrid: keyword + semantic).',
    origin: 'builtin',
    params: [
      { name: 'query', type: 'string', description: 'Search query in any language.', required: true, values: [] },
      {
        name: 'mode',
        type: 'string',
        description: 'Search mode: hybrid (default), semantic, keyword.',
        required: false,
        values: ['hybrid', 'semantic', 'keyword'],
      },
      { name: 'limit', type: 'integer', description: 'Maximum number of results.', required: false, values: [] },
    ],
  },
];

/**
 * Тот же каталог с инструментом внешнего MCP-сервера: у него другой origin
 * (пилюля MCP рядом с именем), схему пишет сервер — отсюда аргумент-массив,
 * которого у собственных инструментов не бывает.
 */
export const withMcpTool = [
  ...builtinTools,
  {
    name: 'fetch_pages',
    description: 'Fetch one or more web pages and return their text content.',
    origin: 'mcp',
    params: [
      { name: 'urls', type: 'array<string>', description: 'Page URLs to fetch.', required: true, values: [] },
    ],
  },
];
