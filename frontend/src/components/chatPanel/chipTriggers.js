// ─── Composer trigger model (/file, /doc) ────────────────────────────────────
// Единое место, где описано «как работает тип триггера»: его команда, regex для
// детектирования у каретки, поисковый запрос и то, какой токен вставляется. Так
// добавление нового типа — это один объект в TRIGGER_TYPES, а не разбросанные по
// компоненту ветки `type === 'file' ? … : …`.

import gitApi from '../../api/gitApi';
import documentsApi from '../../api/documentsApi';
import { makeToken, makeRefToken, makeDocToken, makeDocRefToken } from './fileChips';

/**
 * Поиск документов для триггера `/doc`. Для числового запроса дополнительно
 * пробуем прямой fetch по id и ставим его первым (если ещё не в выдаче по имени).
 */
export async function searchDocsAsync(q, signal) {
  const isNumeric = q.length > 0 && /^\d+$/.test(q);
  if (!isNumeric) {
    return documentsApi.searchByName(q, 10, signal);
  }
  const [nameRes, idRes] = await Promise.all([
    documentsApi.searchByName(q, 10, signal).catch((e) => {
      if (e.name === 'AbortError') throw e;
      return [];
    }),
    documentsApi.fetchById(Number(q), signal).catch((e) => {
      if (e.name === 'AbortError') throw e;
      return null;
    }),
  ]);
  const results = Array.isArray(nameRes) ? [...nameRes] : [];
  if (idRes && !results.find((r) => r.id === idRes.id)) {
    results.unshift(idRes);
  }
  return results;
}

/**
 * Описание типов триггеров. Каждый объект самодостаточен:
 *   trigger      — команда-префикс в тексте
 *   regex        — матч у каретки: (?:^|\s)/cmd\s*(query)$, группа 1 = query
 *   search       — (query, signal) → Promise<node[]>
 *   refToken     — item → строка-токен «только ссылка»
 *   contentToken — item → строка-токен «с содержимым»
 */
export const TRIGGER_TYPES = {
  file: {
    type: 'file',
    trigger: '/file',
    regex: /(?:^|\s)\/file\s*(\S*)$/,
    search: (q, signal) => gitApi.searchFiles(q, 10, signal),
    refToken: (item) => makeRefToken(item.path),
    contentToken: (item) => makeToken(item.path),
  },
  doc: {
    type: 'doc',
    trigger: '/doc',
    regex: /(?:^|\s)\/doc\s*(\S*)$/,
    search: (q, signal) => searchDocsAsync(q, signal),
    refToken: (item) => makeDocRefToken(item.id, item.title),
    contentToken: (item) => makeDocToken(item.id, item.title),
  },
};

// Порядок проверки. Значим только теоретически (команды не пересекаются), но
// сохраняем file→doc как было в исходной ветвлёной логике.
const ORDER = ['file', 'doc'];

/**
 * Найти триггер в тексте перед кареткой.
 * @param {string} before — текст текущего text-узла до позиции каретки
 * @returns {{ type: string, query: string, start: number } | null}
 *   start — индекс начала команды в `before` (для последующей вставки чипа)
 */
export function detectTriggerInText(before) {
  for (const key of ORDER) {
    const spec = TRIGGER_TYPES[key];
    const m = before.match(spec.regex);
    if (m) {
      return { type: spec.type, query: m[1], start: before.lastIndexOf(spec.trigger) };
    }
  }
  return null;
}

/** Токен для выбранного элемента: ссылка либо содержимое, по типу триггера. */
export function tokenForItem(type, item, withContent) {
  const spec = TRIGGER_TYPES[type];
  return withContent ? spec.contentToken(item) : spec.refToken(item);
}
