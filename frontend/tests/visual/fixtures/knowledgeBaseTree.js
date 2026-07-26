/**
 * Фикстуры дерева базы знаний (левая панель раздела, components/
 * knowledgeBasePanel/KnowledgeBase.jsx).
 *
 * Форма узла — как в TreeNode.jsx: id, title, type, parentId, hasChildren,
 * children. hasChildren нужен свёрнутой папке: дети подгружаются лениво, и без
 * этого флага шеврон не рисуется.
 *
 * id синтетические — совпадают с фикстурами detailHeader.js, чтобы кейс
 * «дерево + открытый узел» собирался из двух файлов без правки данных.
 */

/** Одна свёрнутая папка в корне, ни один узел не выбран. */
export const singleFolderTree = [
  {
    id: 'folder-analysis',
    title: 'анализ',
    type: 'folder',
    parentId: null,
    hasChildren: true,
    children: [],
  },
];

/** Та же папка, но раскрытая: два документа внутри. */
export const singleFolderExpanded = [
  {
    id: 'folder-analysis',
    title: 'анализ',
    type: 'folder',
    parentId: null,
    hasChildren: true,
    children: [
      {
        id: 'doc-changelog',
        title: 'Хронология изменений backend/build.gradle',
        type: 'document',
        parentId: 'folder-analysis',
      },
      {
        id: 'doc-links',
        title: 'Пример: ссылки на файлы и документы',
        type: 'document',
        parentId: 'folder-analysis',
      },
    ],
  },
];
