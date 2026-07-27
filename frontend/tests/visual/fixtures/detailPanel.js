/**
 * Фикстуры для детали узла базы знаний: центр (DocumentDetail/FolderDetail с
 * MarkdownEditor) и вкладки правой панели (detailSidebar.jsx).
 *
 * Форма узла — как в дереве (см. TreeNode.jsx) плюс поля summary/summaryStale,
 * от которых зависит вкладка «Summary». `description` — это и есть содержимое,
 * которое правит центр; в правой панели его копии больше нет.
 *
 * id синтетические: реальные id засеянной базы в фикстуры не тащим.
 */

/** Папка-предок для документа ниже. */
export const folderAnalysis = {
  id: 'folder-analysis',
  title: 'анализ',
  type: 'folder',
  parentId: null,
  description: '',
  summary: null,
};

/**
 * Документ с заполненным содержимым и без AI-summary: на нём видно и превью в
 * центре по умолчанию, и пустое состояние вкладки «Summary» с кнопкой генерации.
 */
export const documentWithContent = {
  node: {
    id: 'doc-changelog',
    title: 'Хронология изменений backend/build.gradle',
    type: 'document',
    parentId: folderAnalysis.id,
    description: [
      '# Хронология изменений [`backend/build.gradle`](/files?path=backend/build.gradle)',
      '',
      'Всего **17 коммитов** за период 20.05–13.07.2026.',
      '',
      '## 1. Начальный сетап',
      '**`33195a2`** — 20.05.2026 — *Init frontend/backend*',
      '',
      '- **Spring Boot 3.5.7**, Java 25',
      '- **Spring AI 1.1.5** (OpenAI + chat memory JDBC)',
    ].join('\n'),
    summary: null,
    summaryStale: false,
  },
  path: [folderAnalysis],
};

/** Тот же документ, но с готовым AI-summary — заполненное состояние вкладки. */
export const documentWithSummary = {
  node: {
    ...documentWithContent.node,
    summary: 'Файл сборки бэкенда за два месяца оброс Spring AI, pgvector и Flyway-миграциями.',
    summaryStale: true,
  },
  path: [folderAnalysis],
};

/** Папка с двумя детьми: у неё вкладки «Инфо · Состав · Вложения», без «Summary». */
export const folderWithChildren = {
  node: folderAnalysis,
  path: [],
  children: [
    { id: 'doc-changelog', title: 'Хронология изменений backend/build.gradle', type: 'document' },
    { id: 'doc-links', title: 'Пример: ссылки на файлы', type: 'document' },
  ],
};
