// Что режим «Обзор» показывает для формы «unified diff»: изменения коммита
// (`getCommitDiff`), рабочего дерева (`getUncommittedChanges`) и одиночную
// правку файла (`editFile` / `createFile`).
//
// Разбор — по форме, а не по имени инструмента (см. registry.js). Файловую
// запись diff'а от любой другой записи с путём отличают счётчики строк: пары
// `additions` + `deletions` нет больше ни у одного DTO с полем `path`.

import { nonEmptyString as str } from './fieldValue';

// Больше двух сотен файлов за вызов — это уже не «изменения», а выгрузка;
// разворачивать её построчно бессмысленно, JSON честнее.
const MAX_FILES = 200;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

/**
 * Буква статуса файла: A (added), M (modified), D (deleted), R (renamed),
 * C (copied). `GitDiffEntry` отдаёт её напрямую, `GitEditResult` — через
 * `operation`, у остального diff без статуса считается изменением.
 */
const statusOf = (obj) => {
  const status = str(obj.status);
  if (status) return status.charAt(0).toUpperCase();
  if (obj.operation === 'create') return 'A';
  return 'M';
};

/** Сам патч: `patch` у записей коммита, `diff` у правки файла. */
const patchOf = (obj) => str(obj.patch) ?? str(obj.diff);

/** Одна файловая запись → блок, либо null если форма не та. */
const toFile = (obj, key) => {
  if (!isPlainObject(obj)) return null;
  if (!str(obj.path)) return null;
  if (!Number.isInteger(obj.additions) || !Number.isInteger(obj.deletions)) return null;

  const oldPath = str(obj.oldPath);
  return {
    key,
    path: obj.path,
    // Переименование показываем стрелкой; равные пути — просто шум.
    oldPath: oldPath === obj.path ? null : oldPath,
    status: statusOf(obj),
    additions: obj.additions,
    deletions: obj.deletions,
    patch: patchOf(obj),
  };
};

/** Коммит со списком файлов → группа, либо null если форма не та. */
const toCommit = (obj, key) => {
  if (!isPlainObject(obj) || !Array.isArray(obj.files) || obj.files.length === 0) return null;

  const hash = str(obj.shortHash) ?? str(obj.hash)?.slice(0, 7);
  if (!hash) return null;

  const files = obj.files.map((entry, i) => toFile(entry, `${key}-${i}`));
  if (!files.every(Boolean)) return null;

  return {
    key,
    commit: { hash, author: str(obj.author), date: str(obj.date), message: str(obj.message) },
    files,
  };
};

/** Массив ответа → группы: либо все элементы коммиты, либо все — файлы. */
const groupsOfArray = (parsed) => {
  const commits = parsed.map((entry, i) => toCommit(entry, `commit-${i}`));
  // Все до одного: разнородный список показывается целиком в JSON, иначе часть
  // выдачи молча пропала бы с экрана.
  if (commits.every(Boolean)) return commits;

  const files = parsed.map((entry, i) => toFile(entry, `file-${i}`));
  return files.every(Boolean) ? [{ key: 'files', commit: null, files }] : null;
};

/**
 * Разобранный ответ вызова → группы файлов для `<DiffResultView>`, либо null.
 *
 * Записи без патча (`getCommitDiff` без `includePatch`, только что созданный
 * файл) форму не ломают: вид вырождается в список путей со счётчиками — ровно
 * то, что в них и есть.
 */
export const detectDiffResult = ({ parsed, isJson }) => {
  if (!isJson) return null;

  let groups;
  if (Array.isArray(parsed)) {
    if (parsed.length === 0) return null;
    groups = groupsOfArray(parsed);
  } else {
    const commit = toCommit(parsed, 'commit-0');
    const file = commit ? null : toFile(parsed, 'file-0');
    groups = commit ? [commit] : file ? [{ key: 'files', commit: null, files: [file] }] : null;
  }
  if (!groups) return null;

  const total = groups.reduce((sum, group) => sum + group.files.length, 0);
  return total > 0 && total <= MAX_FILES ? groups : null;
};
