// ─── File chip token model ───────────────────────────────────────────────────
// Файл, вставленный в композер, хранится в плоском тексте как атомарный токен
//   ⟦file:PATH⟧            — весь файл
//   ⟦file:PATH#FROM-TO⟧    — диапазон строк (1-based включительно)
// В UI токен рисуется «чипом» (FileChipInput), а перед отправкой разворачивается
// в markdown-блок с реальным содержимым (expandTokensForSend).

import gitApi from '../../api/gitApi';

const OPEN = '⟦'; // ⟦
const CLOSE = '⟧'; // ⟧

// Глобальный матчер всех токенов в строке. PATH — всё кроме разделителей/закрывающей скобки.
export const TOKEN_RE = new RegExp(`${OPEN}file:([^#${CLOSE}]+)(?:#(\\d+)-(\\d+))?${CLOSE}`, 'g');

/** Собрать строку-токен из пути и опционального диапазона строк. */
export function makeToken(path, from, to) {
  return from != null && to != null
    ? `${OPEN}file:${path}#${from}-${to}${CLOSE}`
    : `${OPEN}file:${path}${CLOSE}`;
}

/** Разобрать одну строку-токен. Возвращает { path, from, to } или null. */
export function parseToken(token) {
  const re = new RegExp(`^${OPEN}file:([^#${CLOSE}]+)(?:#(\\d+)-(\\d+))?${CLOSE}$`);
  const m = token.match(re);
  if (!m) return null;
  return { path: m[1], from: m[2] ? Number(m[2]) : null, to: m[3] ? Number(m[3]) : null };
}

/** Короткое имя файла из пути. */
export function baseName(path) {
  const i = path.lastIndexOf('/');
  return i >= 0 ? path.slice(i + 1) : path;
}

// ── Кеш содержимого ──────────────────────────────────────────────────────────
// Тянем содержимое один раз (при вставке/превью) и переиспользуем при отправке.

const contentCache = new Map(); // key: `path#from-to` → GitFileContent

const cacheKey = (path, from, to) => `${path}#${from ?? ''}-${to ?? ''}`;

/** Получить GitFileContent с кешированием. signal — опц. AbortSignal (превью). */
export async function fetchContent(path, from, to, signal) {
  const key = cacheKey(path, from, to);
  if (contentCache.has(key)) return contentCache.get(key);
  const data = await gitApi.getFileContent(path, from, to, signal);
  contentCache.set(key, data);
  return data;
}

/** Минимальный fence из бэктиков, не конфликтующий с содержимым. */
function fenceFor(content) {
  let longest = 0;
  for (const run of content.matchAll(/`+/g)) longest = Math.max(longest, run[0].length);
  return '`'.repeat(Math.max(3, longest + 1));
}

/**
 * Развернуть все токены в строке в markdown-блоки с содержимым файлов.
 * Бинарные/ошибочные файлы заменяются на короткую пометку, чтобы отправка не падала.
 */
export async function expandTokensForSend(text) {
  const tokens = [...text.matchAll(TOKEN_RE)];
  if (tokens.length === 0) return text;

  // Параллельно тянем содержимое для всех токенов.
  const blocks = await Promise.all(
    tokens.map(async (m) => {
      const parsed = parseToken(m[0]);
      if (!parsed) return m[0];
      const { path, from, to } = parsed;
      try {
        const data = await fetchContent(path, from, to);
        const range = from != null && to != null ? ` (${from}–${to})` : '';
        if (data?.binary) return `\n\n\`${path}\`${range}: [бинарный файл]\n`;
        const content = data?.content ?? '';
        const fence = fenceFor(content);
        const lang = data?.language || '';
        return `\n\n\`${path}\`${range}:\n${fence}${lang}\n${content}\n${fence}\n`;
      } catch {
        return `\n\n\`${path}\`: [не удалось прочитать файл]\n`;
      }
    }),
  );

  // Подставляем блоки по порядку появления токенов.
  let i = 0;
  return text.replace(TOKEN_RE, () => blocks[i++]);
}
