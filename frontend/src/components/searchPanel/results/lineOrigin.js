// Ответ «когда появилось» (GET /api/git/files/origin → GitLineOrigin) в то,
// что рисует LineOrigin: какая подпись, какой коммит назван ответом, есть ли
// путь строки, который стоит показывать.

/**
 * @returns {{
 *   kind: 'found' | 'boundary' | 'limit' | 'uncommitted' | 'notInLine',
 *   origin: object | null,   // шаг-ответ: где появилось (или самый старый из пройденных)
 *   before: object | null,   // строка до появления — уже без подстроки
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
      return { ...base, kind: 'uncommitted', origin: null };
    default:
      return { ...base, kind: 'notInLine', origin: null };
  }
}

/**
 * Ключ запроса: всё, от чего зависит ответ. Тот же файл и строка в другой
 * ревизии или под другим запросом — другой ответ.
 */
export const originKey = ({ path, line, query, rev, project }) =>
  JSON.stringify([project ?? '', rev ?? '', path, line, query]);
