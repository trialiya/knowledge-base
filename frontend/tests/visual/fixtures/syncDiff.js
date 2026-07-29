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

/**
 * Журнал того же импорта — строки, которые собираются из кадров PROGRESS.
 *
 * Записан с живого прогона и специально включает три вещи, которых нет в
 * сводке: отказ с причиной от сервера, узел, попавший в журнал дважды (создан,
 * потом переписан вторым проходом по ссылкам), и порядок — сначала весь первый
 * проход, только потом ссылки.
 */
export const importLogLines = [
  {
    path: 'анализ/хронология-изменений-backend-build-gradle',
    action: 'failed',
    message: 'type changed on disk',
  },
  { path: 'анализ/пример-ссылки-на-файлы-и-документы', action: 'updated', message: null },
  { path: 'анализ/alpha', action: 'created', message: null },
  { path: 'анализ/beta', action: 'created', message: null },
  { path: 'novaya-papka', action: 'created', message: null },
  { path: 'novaya-papka/rebenok', action: 'created', message: null },
  { path: 'анализ/пример-ссылки-на-файлы-и-документы', action: 'relinked', message: null },
  { path: 'анализ/alpha', action: 'relinked', message: null },
];

/** Тот же журнал целиком — как его хранит syncLog.js. */
export const importLog = { lines: importLogLines, dropped: 0 };

/** Сводка прогона, оставившего этот журнал. */
export const importWithFailureSummary = {
  created: 4,
  updated: 1,
  deleted: 0,
  relinked: 2,
  failed: 1,
};
