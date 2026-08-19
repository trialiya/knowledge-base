// Что чат достаёт из результата вызова инструмента, чтобы показать под ответом ИИ
// блок изменений. Имена, иконки и лейблы инструментов — в common/toolNames.js.

import { TOOL_STATUS } from '../../../constants/toolStatus';

// ── Документные мутации ───────────────────────────────────────────────────────
// Инструменты, которые меняют документ и возвращают resultMeta { id, version }.
// Для них в конце ответа ИИ показываем блок «посмотреть изменения» (см. MessageList.jsx),
// открывающий HistoryModal на нужной версии.
export const DOC_MUTATION_TOOLS = new Set([
  'createDocument',
  'updateDocument',
  'updateDocumentSection',
  'insertDocumentSection',
  'deleteDocumentSection',
  'renameDocumentSections',
]);

// ── Файловые мутации (git) ────────────────────────────────────────────────────
// Инструменты, меняющие файлы рабочего дерева. createFile/editFile правят один
// файл и кладут его в корень resultMeta: { path, operation, additions, deletions,
// lineCount, diff? }. runScript правит пачкой — те же записи приходят массивом в
// resultMeta.edits. Для всех них под ответом ИИ показываем блок «изменённые
// файлы» (FileChangeBlock.jsx) с diff-модалкой.
export const FILE_MUTATION_TOOLS = new Set(['createFile', 'editFile', 'runScript']);

/** Одна запись мутации → { path, operation, additions, deletions, diff, status } или null. */
const toFileChangeRef = (meta, status) => {
  if (!meta || !meta.path) return null;
  return {
    path: String(meta.path),
    operation: meta.operation === 'create' ? 'create' : 'edit',
    additions: Number(meta.additions) || 0,
    deletions: Number(meta.deletions) || 0,
    diff: typeof meta.diff === 'string' && meta.diff ? meta.diff : null,
    status,
  };
};

/**
 * Все файловые правки одного tool call. Список, а не одна запись, потому что
 * runScript за вызов меняет несколько файлов; для createFile/editFile это всегда
 * ноль или один элемент. Не файловая мутация или пустой resultMeta — пустой массив.
 */
export const getFileChangeRefs = (tc) => {
  if (!tc || !FILE_MUTATION_TOOLS.has(tc.name)) return [];
  const meta = tc.resultMeta;
  if (!meta) return [];
  if (Array.isArray(meta.edits)) {
    return meta.edits.map((edit) => toFileChangeRef(edit, tc.status)).filter(Boolean);
  }
  const ref = toFileChangeRef(meta, tc.status);
  return ref ? [ref] : [];
};

/**
 * Если tc — документная мутация с валидным resultMeta — вернуть
 * { id, version, action, status } для блока «посмотреть изменения», иначе null.
 *
 * `version` берётся из resultMeta.version и трактуется как descriptionVersion
 * (история изменений описания нумеруется так же) — по нему HistoryModal наводится
 * на конкретную правку. `title` бэкенд кладёт в resultMeta для обоих инструментов.
 */
export const getDocChangeRef = (tc) => {
  if (!tc || !DOC_MUTATION_TOOLS.has(tc.name)) return null;
  const meta = tc.resultMeta;
  if (!meta || meta.id == null) return null;
  return {
    id: String(meta.id), // в стриме приходит числом (55), в истории строкой ("55")
    parentId: meta.parent ?? null,
    descriptionVersion:
      typeof meta.descriptionVersion === 'number' ? meta.descriptionVersion : Number(meta.descriptionVersion) || null,
    title: meta.title ?? null,
    action: tc.name,
    status: tc.status,
  };
};

/**
 * Успешные doc/file-мутации набора вызовов инструментов — вход для инвалидации кэшей
 * базы знаний и файлов (см. App.jsx). Ошибочный вызов откатан на бэке, инвалидировать
 * по нему нечего, поэтому такие записи отсеиваем.
 *
 * Один общий сбор на два источника: живые события прогона и его же вызовы, вычитанные
 * из истории, когда события прошли мимо (см. useChatEventStream).
 *
 * @param {Array} toolCalls вызовы в форме metaToCall/TOOL_CALLS-события
 * @returns {{ docRefs: Array, fileRefs: Array }} оба массива могут быть пустыми
 */
export const collectChangeRefs = (toolCalls) => {
  const docRefs = [];
  const fileRefs = [];
  for (const tc of toolCalls || []) {
    const docRef = getDocChangeRef(tc);
    if (docRef && docRef.status !== TOOL_STATUS.ERROR) docRefs.push(docRef);
    // Один вызов может принести несколько правок: runScript пишет пачкой.
    for (const fileRef of getFileChangeRefs(tc)) {
      if (fileRef.status !== TOOL_STATUS.ERROR) fileRefs.push(fileRef);
    }
  }
  return { docRefs, fileRefs };
};
