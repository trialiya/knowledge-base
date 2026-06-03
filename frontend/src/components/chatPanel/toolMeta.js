// src/components/chat/toolMeta.js
//
// Реестр инструментов: иконка/категория и фолбэк-имя.
// Человекочитаемые ЛЕЙБЛЫ живут в i18n под ключом `tools.<name>` (см. locales/*.json).
// Здесь хранится только то, что не относится к переводу.
//
// Ключ — техническое имя инструмента с бэкенда:
//   delegate.getToolDefinition().name()

export const TOOL_META = {
  // ── Документы ──
  searchDocuments: { icon: '🔎', category: 'doc' },
  findDocumentsByName: { icon: '🔎', category: 'doc' },
  getTreeSkeleton: { icon: '🌳', category: 'doc' },
  getDocument: { icon: '📄', category: 'doc' },
  createDocument: { icon: '➕', category: 'doc' },
  updateDocument: { icon: '✏️', category: 'doc' },
  copyAttachmentToDocument: { icon: '📎', category: 'doc' },
  // ── Вложения ──
  getDocumentAttachments: { icon: '📎', category: 'attachment' },
  getChatAttachments: { icon: '📎', category: 'attachment' },
  getAttachmentContent: { icon: '📎', category: 'attachment' },
  getAttachmentContentByFileName: { icon: '📎', category: 'attachment' },
  createAttachment: { icon: '📎', category: 'attachment' },
  searchAttachments: { icon: '🔎', category: 'attachment' },
  // ── Чат / служебные ──
  getOriginalMessages: { icon: '💬', category: 'chat' },
  getChatId: { icon: '🆔', category: 'chat' },
  getUserName: { icon: '👤', category: 'chat' },
  getCurrentDateTime: { icon: '🕒', category: 'chat' },
  recordChatInsights: { icon: '🏷️', category: 'chat' },
  // ── Git ──
  getFileTree: { icon: '🌳', category: 'git' },
  getCommitLog: { icon: '📜', category: 'git' },
  getCommitDiff: { icon: '🔀', category: 'git' },
  searchFiles: { icon: '🔎', category: 'git' },
  getFileOutline: { icon: '🗂️', category: 'git' },
  getFileContent: { icon: '📄', category: 'git' },
  getUncommittedChanges: { icon: '📝', category: 'git' },
  grepContent: { icon: '🔎', category: 'git' },
};

/** Иконка инструмента с дефолтом для незнакомых имён. */
export const getToolIcon = (name) => TOOL_META[name]?.icon ?? '🔧';

/** i18n-ключ лейбла. Использовать как t(toolLabelKey(name), { defaultValue: humanizeTool(name) }). */
export const toolLabelKey = (name) => `tools.${name}`;

/**
 * Фолбэк, если для инструмента нет перевода:
 * camelCase / snake_case → "Camel Case".
 * Подставляется через defaultValue, чтобы вместо сырого ключа
 * пользователь увидел хотя бы читабельное имя.
 */
export const humanizeTool = (name) =>
  String(name || '')
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/^\w/, (c) => c.toUpperCase());
