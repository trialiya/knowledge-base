// Что режим «Обзор» показывает для ответа `getLineOrigin`: коммит, где подстрока
// вошла в строку, строку до него и путь строки — каждую её версию, пока
// подстрока в ней жила. Что считать ответом при каком статусе, решает общий с
// поиском `describeOrigin`.
//
// Разбор — по форме (см. registry.js): объект с путём, номером строки, запросом,
// статусом и массивом `steps`, у каждого шага — хеш, номер строки и текст.

import { isPlainObject } from './contentResult';
import { describeOrigin, versionLink } from '@/components/common/git/lineOrigin';
import { filesUrl } from '@/navigation/urlScheme';
import shortRev from '@/components/common/git/shortRev';

const isLine = (value) => Number.isInteger(value) && value >= 1;

const isStep = (step) =>
  isPlainObject(step) && typeof step.hash === 'string' && isLine(step.line) && typeof step.text === 'string';

/**
 * Шаг → строка вида. Ссылка — общая с панелью поиска (`versionLink`): файл в
 * снимке того коммита по его пути и номеру строки, с вкладкой «Коммит».
 */
const toStep = (step, file, query, project, before) => ({
  ...step,
  boundary: step.boundary === true,
  href: filesUrl(
    typeof step.path === 'string' && step.path ? step.path : file,
    project,
    versionLink(step, query, { before }),
  ),
});

/** Разобранный ответ вызова → данные для `<OriginResultView>`, либо null. */
export const detectOriginResult = ({ parsed, isJson, project = null }) => {
  if (!isJson || !isPlainObject(parsed)) return null;
  const { path, line, query, commit, status, steps, before } = parsed;
  if (typeof path !== 'string' || !path || !isLine(line) || typeof query !== 'string' || !query) return null;
  if (typeof status !== 'string' || !Array.isArray(steps) || !steps.every(isStep)) return null;
  if (before != null && !isStep(before)) return null;

  const view = describeOrigin(parsed);
  const step = (value, before = false) => (value ? toStep(value, path, query, project, before) : null);
  const rev = typeof commit === 'string' && commit ? commit : null;
  return {
    kind: view.kind,
    path,
    line,
    query,
    shortRev: rev ? shortRev(rev) : null,
    href: filesUrl(path, project, { rev: rev ?? undefined, lines: String(line), find: query }),
    origin: step(view.origin),
    before: step(view.before, true),
    versions: view.path.map((value) => step(value)),
    project,
  };
};
