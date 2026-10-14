// Ответ «когда появилось» (GitLineOrigin: GET /api/git/files/origin и инструмент
// модели getLineOrigin) в то, что рисуют оба его вида — панель под строкой поиска
// (searchPanel/results/LineOrigin.jsx) и «Обзор» вызова в чате: какая подпись,
// какой коммит назван ответом, есть ли путь строки, который стоит показывать.

import { FILE_TAB } from '@/constants/fileTabs';

/**
 * Куда ведёт версия строки: файл в снимке того коммита, его номер строки и вкладка
 * «Коммит». Запрос уходит в find-бар везде, кроме `before`: в той версии подстроки
 * по определению нет, и find-бар искал бы впустую.
 *
 * @param before это версия «до» — строка уже без подстроки
 * @returns параметры `filesUrl`/`openFilePath` (без пути: путь — `step.path`)
 */
export const versionLink = (step, query, { before = false } = {}) => ({
  rev: step.hash,
  lines: String(step.line),
  ...(before ? {} : { find: query }),
  right: FILE_TAB.COMMIT,
});

/**
 * @returns {{
 *   kind: 'found' | 'boundary' | 'limit' | 'uncommitted' | 'notInLine',
 *   origin: object | null,   // шаг-ответ: где появилось (или самый старый из пройденных)
 *   before: object | null,   // строка до появления — уже без подстроки (у незакоммиченной
 *                            // правки — строка в HEAD, которую она заменила)
 *   path: object[],          // пройденные версии строки от новой к старой
 * }}
 */
export function describeOrigin(answer) {
  const steps = Array.isArray(answer?.steps) ? answer.steps : [];
  const origin = steps.length ? steps[steps.length - 1] : null;
  const base = { origin, before: answer?.before ?? null, path: steps };
  switch (answer?.status) {
    case 'FOUND':
      return origin ? { ...base, kind: 'found' } : { ...base, kind: 'notInLine' };
    case 'BOUNDARY':
      return { ...base, kind: 'boundary' };
    case 'LIMIT':
      return { ...base, kind: 'limit' };
    case 'UNCOMMITTED':
      return { ...base, kind: 'uncommitted', origin: null, path: [] };
    default:
      return { ...base, kind: 'notInLine', origin: null, before: null, path: [] };
  }
}
