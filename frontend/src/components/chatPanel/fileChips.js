// ─── File chip token model ───────────────────────────────────────────────────
// Файл в композере хранится как атомарный токен:
//   ⟦file:PATH⟧            — весь файл (раскрывается в fenced-блок при отправке)
//   ⟦file:PATH#FROM-TO⟧    — диапазон строк (1-based включительно)
//   ⟦ref:PATH⟧             — только ссылка (раскрывается в `PATH`)

import gitApi from '../../api/gitApi';
import documentsApi from '../../api/documentsApi';

const OPEN = '⟦'; // ⟦
const CLOSE = '⟧'; // ⟧

// Глобальный матчер всех видов токенов. Захватных групп нет — parse* разбирают детально.
// docref идёт перед doc, чтобы не срабатывал prefix-match при чтении.
export const TOKEN_RE = new RegExp(`${OPEN}(?:file|ref|docref|doc):[^${CLOSE}]+${CLOSE}`, 'g');

/** Токен «весь файл / диапазон». */
export function makeToken(path, from, to) {
  return from != null && to != null ? `${OPEN}file:${path}#${from}-${to}${CLOSE}` : `${OPEN}file:${path}${CLOSE}`;
}

/** Токен «только путь» (без раскрытия содержимого). */
export function makeRefToken(path) {
  return `${OPEN}ref:${path}${CLOSE}`;
}

/**
 * Разобрать строку-токен.
 * Возвращает { path, from, to, refOnly } или null.
 */
export function parseToken(token) {
  const fileRe = new RegExp(`^${OPEN}file:([^#${CLOSE}]+)(?:#(\\d+)-(\\d+))?${CLOSE}$`);
  const fm = token.match(fileRe);
  if (fm) {
    return { path: fm[1], from: fm[2] ? Number(fm[2]) : null, to: fm[3] ? Number(fm[3]) : null, refOnly: false };
  }
  const refRe = new RegExp(`^${OPEN}ref:([^${CLOSE}]+)${CLOSE}$`);
  const rm = token.match(refRe);
  if (rm) return { path: rm[1], from: null, to: null, refOnly: true };
  return null;
}

/** Короткое имя файла из пути. */
export function baseName(path) {
  const i = path.lastIndexOf('/');
  return i >= 0 ? path.slice(i + 1) : path;
}

// ── Doc-токены ────────────────────────────────────────────────────────────────
// ⟦doc:ID:TITLE⟧    — полное содержимое (описание документа)
// ⟦docref:ID:TITLE⟧ — только упоминание (без содержимого)

function safeDocTitle(title) {
  return String(title).replace(/⟧/g, '');
}

/** Токен документа с раскрытием описания при отправке. */
export function makeDocToken(id, title) {
  return `${OPEN}doc:${id}:${safeDocTitle(title)}${CLOSE}`;
}

/** Токен документа — только упоминание (без описания). */
export function makeDocRefToken(id, title) {
  return `${OPEN}docref:${id}:${safeDocTitle(title)}${CLOSE}`;
}

/**
 * Разобрать doc-токен (с содержимым).
 * Возвращает { id, title } или null.
 */
export function parseDocToken(token) {
  const m = token.match(new RegExp(`^${OPEN}doc:(\\d+):(.*)${CLOSE}$`));
  if (m) return { id: Number(m[1]), title: m[2] };
  return null;
}

/**
 * Разобрать docref-токен (только ссылка).
 * Возвращает { id, title } или null.
 */
export function parseDocRefToken(token) {
  const m = token.match(new RegExp(`^${OPEN}docref:(\\d+):(.*)${CLOSE}$`));
  if (m) return { id: Number(m[1]), title: m[2] };
  return null;
}

// ── Кеш содержимого ──────────────────────────────────────────────────────────

const contentCache = new Map(); // key: `path#from-to` → GitFileContent

const cacheKey = (path, from, to) => `${path}#${from ?? ''}-${to ?? ''}`;

/** Получить GitFileContent с кешированием. */
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
 * Развернуть все токены в строке:
 *  ⟦file:PATH⟧    → fenced code block с содержимым
 *  ⟦ref:PATH⟧     → `PATH`
 */
export async function expandTokensForSend(text) {
  const tokens = [...text.matchAll(TOKEN_RE)];
  if (tokens.length === 0) return text;

  const blocks = await Promise.all(
    tokens.map(async (m) => {
      const docRefParsed = parseDocRefToken(m[0]);
      if (docRefParsed) {
        return `Документ «${docRefParsed.title}» (#${docRefParsed.id})`;
      }

      const docParsed = parseDocToken(m[0]);
      if (docParsed) {
        try {
          const doc = await documentsApi.fetchById(docParsed.id);
          const title = doc?.title ?? docParsed.title;
          const description = doc?.description ?? '';
          return `\n\nДокумент «${title}» (#${docParsed.id}):\n${description}\n`;
        } catch {
          return `\n\n[Документ #${docParsed.id}: не удалось загрузить]\n`;
        }
      }

      const parsed = parseToken(m[0]);
      if (!parsed) return m[0];
      const { path, from, to, refOnly } = parsed;

      if (refOnly) return `\`${path}\``;

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

  let i = 0;
  return text.replace(TOKEN_RE, () => blocks[i++]);
}
