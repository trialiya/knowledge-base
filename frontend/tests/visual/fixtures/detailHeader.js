/**
 * Фикстуры для шапки узла базы знаний (knowledgeBasePanel/DetailHeader.jsx).
 *
 * Форма узла — как в дереве базы знаний (см. TreeNode.jsx): id, title, type
 * ('folder' | 'document'), parentId, system. `path` — массив ПРЕДКОВ от корня,
 * сам узел в него не входит (см. findPath в components/common/utils.js), поэтому
 * у крошек в шапке есть замыкающий разделитель.
 *
 * id синтетические: реальные id засеянной базы в фикстуры не тащим.
 */

/** Папка в корне базы знаний — предок для документов ниже. */
export const folderRoot = {
  id: 'folder-analysis',
  title: 'анализ',
  type: 'folder',
  parentId: null,
};

/** Документ внутри одной папки — ровно один предок, крошки в строку помещаются. */
export const documentInFolder = {
  node: {
    id: 'doc-changelog',
    title: 'Хронология изменений backend/build.gradle',
    type: 'document',
    parentId: folderRoot.id,
    description: '# Хронология изменений\n\nВсего **17 коммитов** за период 20.05–13.07.2026.\n',
  },
  path: [folderRoot],
};

/**
 * Документ на глубине, где крошки заведомо шире шапки: проверяет, что строка не
 * переносится, а уезжает в горизонтальный скролл, прижатый к концу пути.
 * В db/sample-data.sql такого дерева нет — данные собраны специально под кейс.
 */
export const documentDeepPath = {
  node: {
    id: 'doc-tool-storage',
    title: 'Хранение вызовов инструментов',
    type: 'document',
    parentId: 'folder-persistence',
  },
  path: [
    folderRoot,
    { id: 'folder-backend', title: 'backend', type: 'folder', parentId: folderRoot.id },
    { id: 'folder-architecture', title: 'архитектура', type: 'folder', parentId: 'folder-backend' },
    { id: 'folder-chat', title: 'чат и память диалога', type: 'folder', parentId: 'folder-architecture' },
    { id: 'folder-persistence', title: 'персистентность сообщений', type: 'folder', parentId: 'folder-chat' },
  ],
};

/**
 * Системный узел: переименование и удаление запрещены, вместо кнопок замок.
 * В db/sample-data.sql строк с is_system = TRUE нет — фикстура закрывает эту дыру.
 */
export const systemDocument = {
  node: {
    id: 'doc-system',
    title: 'Инструкции ассистента',
    type: 'document',
    parentId: folderRoot.id,
    system: true,
  },
  path: [folderRoot],
};

/** Папка внутри папки — вариант иконки --folder, и крошки при этом непустые. */
export const folderInFolder = {
  node: {
    id: 'folder-backend',
    title: 'backend',
    type: 'folder',
    parentId: folderRoot.id,
  },
  path: [folderRoot],
};
