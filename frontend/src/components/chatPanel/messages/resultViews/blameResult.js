// Что режим «Обзор» показывает для ответа `getBlame`: ханки — подряд идущие
// строки одного коммита — строкой на ханк, с хешем, автором, датой и
// описанием коммита.
//
// Разбор — по форме (см. registry.js): объект с путём и массивом `hunks`, у
// каждого ханка числовые `fromLine` и `lineCount`. Так выглядит и ответ REST
// колонки blame (`GitFileBlame`), но в модалку вызова приходит только ответ
// инструмента.

import { isPlainObject } from './contentResult';
import { filesUrl, formatLines } from '@/navigation/urlScheme';
import { FILE_TAB } from '@/constants/fileTabs';
import shortRev from '@/components/common/git/shortRev';

const isLine = (value) => Number.isInteger(value) && value >= 1;

const isHunk = (hunk) => isPlainObject(hunk) && isLine(hunk.fromLine) && isLine(hunk.lineCount);

const HASH = /^[0-9a-f]{7,64}$/i;

/**
 * Ханк → строка вида. Ссылка с номеров строк ведёт туда же, куда ячейка
 * колонки blame в «Файлах»: файл в снимке того коммита, по его пути и его
 * номерам строк (`path`, `sourceLine`), с открытой вкладкой «Коммит».
 */
const toRow = (hunk, i, file, project) => {
  const to = hunk.fromLine + hunk.lineCount - 1;
  const lines = to === hunk.fromLine ? String(hunk.fromLine) : `${hunk.fromLine}–${to}`;
  const hash = typeof hunk.hash === 'string' && HASH.test(hunk.hash) ? hunk.hash : null;
  if (!hash) return { key: `hunk-${i}`, lines, uncommitted: true };

  const source = isLine(hunk.sourceLine) ? formatLines(hunk.sourceLine, hunk.lineCount) : undefined;
  return {
    key: `hunk-${i}`,
    lines,
    uncommitted: false,
    hash,
    shortHash: shortRev(hash),
    author: typeof hunk.author === 'string' ? hunk.author : null,
    date: typeof hunk.date === 'string' ? hunk.date : null,
    summary: typeof hunk.summary === 'string' ? hunk.summary : null,
    href: filesUrl(typeof hunk.path === 'string' && hunk.path ? hunk.path : file, project, {
      rev: hash,
      lines: source,
      right: FILE_TAB.COMMIT,
    }),
  };
};

/** Разобранный ответ вызова → данные для `<BlameResultView>`, либо null. */
export const detectBlameResult = ({ parsed, isJson, project = null, truncated = false }) => {
  if (!isJson || !isPlainObject(parsed)) return null;
  const { path, commit, lineCount, hunks, fromLine, toLine } = parsed;
  if (typeof path !== 'string' || !path || !Number.isInteger(lineCount) || !Array.isArray(hunks)) return null;
  if (!hunks.every(isHunk)) return null;

  const rev = typeof commit === 'string' && commit ? commit : null;
  const ranged = isLine(fromLine) && Number.isInteger(toLine);
  return {
    path,
    rev,
    shortRev: rev ? shortRev(rev) : null,
    lineCount,
    // toLine < fromLine — от файла в диапазон не попало ничего (GitFileBlame).
    range: ranged ? { from: fromLine, to: toLine, empty: toLine < fromLine } : null,
    href: filesUrl(path, project, {
      rev: rev ?? undefined,
      lines: ranged && toLine >= fromLine ? formatLines(fromLine, toLine - fromLine + 1) : undefined,
    }),
    rows: hunks.map((hunk, i) => toRow(hunk, i, path, project)),
    project,
    truncated,
  };
};
