/**
 * Фикстуры списка различий (SyncDiffList) — записи, которые присылает
 * GET /api/documents/admin/import/diff кадрами ENTRY.
 *
 * Данные синтетические, но собраны так же, как их отдаёт бэк на реальном
 * прогоне: сначала совпадающие узлы уже выгруженного дерева, потом правки,
 * которые человек внёс в папке экспорта руками. Именно эта смесь интересна
 * визуально — из шести записей действий требуют четыре, и без фильтра
 * «показывать совпадающие» их не видно за остальными.
 */

/** Дерево после экспорта: правка в существующем документе, новый документ рядом и целая новая папка. */
export const mixedDiffEntries = [
  {
    path: 'анализ',
    title: 'анализ',
    type: 'folder',
    status: 'unchanged',
    docId: 75,
    depth: 0,
  },
  {
    path: 'анализ/хронология-изменений-backend-build-gradle',
    title: 'Хронология изменений backend/build.gradle',
    type: 'document',
    status: 'unchanged',
    docId: 76,
    depth: 1,
  },
  {
    path: 'анализ/пример-ссылки-на-файлы-и-документы',
    title: 'Пример: ссылки на файлы и документы',
    type: 'document',
    status: 'modified',
    docId: 77,
    depth: 1,
  },
  {
    path: 'анализ/novyi-dokument',
    title: 'novyi-dokument',
    type: 'document',
    status: 'added',
    docId: null,
    depth: 1,
  },
  {
    path: 'novaya-papka',
    title: 'novaya-papka',
    type: 'folder',
    status: 'added',
    docId: null,
    depth: 0,
  },
  {
    path: 'novaya-papka/rebenok',
    title: 'rebenok',
    type: 'document',
    status: 'added',
    docId: null,
    depth: 1,
  },
];

/** Итоговый кадр DONE того же прогона. */
export const mixedDiffSummary = { added: 3, modified: 1, unchanged: 2, missing: 0 };

/** Узел, удалённый из папки экспорта, — единственный статус, который может что-то стереть. */
export const missingEntry = {
  path: 'анализ/удалённый-с-диска',
  title: 'Удалённый с диска',
  type: 'document',
  status: 'missing',
  docId: 78,
  depth: 1,
};

/** Сводка импорта, применившего новую папку с ребёнком. */
export const importSummary = { created: 2, updated: 0, deleted: 0, relinked: 0, failed: 0 };
