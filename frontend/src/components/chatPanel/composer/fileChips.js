// ─── File chip token model ───────────────────────────────────────────────────
// Файл в композере хранится как атомарный токен:
//   ⟦file@PROJECT:PATH⟧            — весь файл (раскрывается в fenced-блок при отправке)
//   ⟦file@PROJECT:PATH#FROM-TO⟧    — диапазон строк (1-based включительно)
//   ⟦ref@PROJECT:PATH⟧             — только ссылка (раскрывается в `PATH`)
//   ⟦commit@PROJECT:HASH:SUBJECT⟧  — коммит (раскрывается в хэш + тему, без запроса)
//
// Проект в токене — потому что путь `backend/pom.xml` есть в каждом репозитории, а
// хэш коммита — ровно в одном: без имени проекта чип означал бы «тот репозиторий,
// что выбран в чате сейчас», и смена проекта переписывала бы смысл уже набранного.
// Форма `@ID`, а не второй сегмент через двоеточие: двоеточие в пути законно, и
// `⟦file:docs:notes.md⟧` было бы не отличить от проекта `docs`. После имени вида
// стоит либо `@` (проект назван), либо `:` (не назван) — разбор однозначен.
//
// Проект НЕ обязателен: токены без него лежат в сохранённых черновиках и означают
// «репозиторий чата, из которого уходит сообщение». Смена проекта в чате вписывает
// прежний проект в такие токены (stampChipProject) — там, где «прежний» ещё известен.
//
// doc/docref проект не несут и не могут: база знаний общая для всех проектов.

import gitApi from '../../../api/gitApi';
import documentsApi from '../../../api/documentsApi';
// i18n-инстанс напрямую: модуль не компонент, useTranslation здесь недоступен.
// Строки уходят в текст отправляемого сообщения и следуют языку интерфейса.
import i18n from '../../../i18n';
export { baseName } from '../../common/utils';

const OPEN = '⟦'; // ⟦
const CLOSE = '⟧'; // ⟧

// Тот же набор символов, что у id проекта на бэкенде (ProjectCatalog.SAFE_ID):
// расширится он — расширится и здесь, иначе новый id перестанет читаться в токене.
const PROJECT_ID = '[a-z0-9][a-z0-9._-]*';
// Хвост «@проект» или пусто; группа 1 везде — проект.
const AT_PROJECT = `(?:@(${PROJECT_ID}))?`;

// Глобальный матчер всех видов токенов. Захватных групп нет — parse* разбирают детально.
// docref идёт перед doc, чтобы не срабатывал prefix-match при чтении.
export const TOKEN_RE = new RegExp(
  `${OPEN}(?:file|ref|docref|doc|commit)(?:@${PROJECT_ID})?:[^${CLOSE}]+${CLOSE}`,
  'g',
);

// Виды, привязанные к репозиторию. doc/docref сюда не входят — им проект не нужен.
const UNQUALIFIED_RE = new RegExp(`${OPEN}(file|ref|commit):`, 'g');

/**
 * Вписать проект в чипы, которые его не называют. Токен без проекта означает
 * «репозиторий чата», поэтому вписывать надо ровно в тот момент, когда чат ещё
 * работает в нём: при смене проекта — прежний. Бэкенд делает то же самое с уже
 * сохранёнными ссылками на файлы (FileLinkProjectBackfillService).
 */
export function stampChipProject(text, project) {
  if (!text || !project) return text;
  return text.replace(UNQUALIFIED_RE, `${OPEN}$1@${project}:`);
}

const at = (project) => (project ? `@${project}` : '');

/**
 * Подпись чипа: имя проекта приписывается только к чужому — у чипа из репозитория
 * чата приписывать нечего, а в поле ввода их большинство.
 */
export function chipLabel(own, current, text) {
  return own && own !== current ? `${own} · ${text}` : text;
}

/** Токен «весь файл / диапазон». */
export function makeToken(path, { from, to, project } = {}) {
  const range = from != null && to != null ? `#${from}-${to}` : '';
  return `${OPEN}file${at(project)}:${path}${range}${CLOSE}`;
}

/** Токен «только путь» (без раскрытия содержимого). */
export function makeRefToken(path, project) {
  return `${OPEN}ref${at(project)}:${path}${CLOSE}`;
}

/**
 * Разобрать строку-токен.
 * Возвращает { project, path, from, to, refOnly } или null. `project === null` —
 * токен старого образца: он о репозитории чата, чей это черновик.
 */
export function parseToken(token) {
  const fileRe = new RegExp(`^${OPEN}file${AT_PROJECT}:([^#${CLOSE}]+)(?:#(\\d+)-(\\d+))?${CLOSE}$`);
  const fm = token.match(fileRe);
  if (fm) {
    return {
      project: fm[1] ?? null,
      path: fm[2],
      from: fm[3] ? Number(fm[3]) : null,
      to: fm[4] ? Number(fm[4]) : null,
      refOnly: false,
    };
  }
  const refRe = new RegExp(`^${OPEN}ref${AT_PROJECT}:([^${CLOSE}]+)${CLOSE}$`);
  const rm = token.match(refRe);
  if (rm) return { project: rm[1] ?? null, path: rm[2], from: null, to: null, refOnly: true };
  return null;
}

// ── Doc-токены ────────────────────────────────────────────────────────────────
// ⟦doc:ID:TITLE⟧    — полное содержимое (описание документа)
// ⟦docref:ID:TITLE⟧ — только упоминание (без содержимого)

// Подпись внутри токена — часть плоской строки-значения: закрывающая скобка
// оборвала бы токен, перенос строки разрезал бы его на две строки редактора.
function safeChipLabel(text) {
  return String(text)
    .replace(/⟧/g, '')
    .replace(/\s*\n\s*/g, ' ');
}

/** Токен документа с раскрытием описания при отправке. */
export function makeDocToken(id, title) {
  return `${OPEN}doc:${id}:${safeChipLabel(title)}${CLOSE}`;
}

/** Токен документа — только упоминание (без описания). */
export function makeDocRefToken(id, title) {
  return `${OPEN}docref:${id}:${safeChipLabel(title)}${CLOSE}`;
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

// ── Commit-токены ─────────────────────────────────────────────────────────────
// ⟦commit@PROJECT:HASH:SUBJECT⟧ — тема коммита едет внутри токена, поэтому раскрытие
// при отправке не ходит в сеть: хэша достаточно, чтобы модель сама достала диф.

/** Токен коммита. `subject` — первая строка сообщения. */
export function makeCommitToken(hash, subject, project) {
  return `${OPEN}commit${at(project)}:${hash}:${safeChipLabel(subject)}${CLOSE}`;
}

/**
 * Разобрать commit-токен.
 * Возвращает { project, hash, subject } или null.
 */
export function parseCommitToken(token) {
  const m = token.match(new RegExp(`^${OPEN}commit${AT_PROJECT}:([^:${CLOSE}]+):(.*)${CLOSE}$`));
  if (m) return { project: m[1] ?? null, hash: m[2], subject: m[3] };
  return null;
}

// ── Кеш содержимого ──────────────────────────────────────────────────────────

const contentCache = new Map(); // key: `project\0path#from-to` → GitFileContent

// Проект — часть ключа: один и тот же путь есть в каждом репозитории, и общий
// кэш подставил бы в чип файл из другого проекта, ничем не выдав подмены.
const cacheKey = (project, path, from, to) => `${project || ''}\u0000${path}#${from ?? ''}-${to ?? ''}`;

/** Получить GitFileContent с кешированием. */
export async function fetchContent(path, { from, to, project, signal } = {}) {
  const key = cacheKey(project, path, from, to);
  if (contentCache.has(key)) return contentCache.get(key);
  const data = await gitApi.getFileContent(path, { from, to, project, signal });
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
 *  ⟦file@P:PATH⟧            → fenced code block с содержимым
 *  ⟦ref@P:PATH⟧             → `PATH`
 *  ⟦commit@P:HASH:SUBJECT⟧  → `HASH` + тема
 *
 * `project` — репозиторий чата: им разрешаются токены, которые проект не назвали
 * (старая форма из сохранённых черновиков). Названный проект берётся из самого
 * токена и, если он не проектом чата, называется вслух рядом с путём: для модели
 * это соседний репозиторий, и без имени она прочитает путь как свой.
 */
export async function expandTokensForSend(text, project) {
  const tokens = [...text.matchAll(TOKEN_RE)];
  if (tokens.length === 0) return text;

  // Метка «это из другого репозитория» — пусто для проекта чата и для токенов,
  // которые проект не называют: там репозиторий и так один, называть нечего.
  const foreign = (own) => (own && own !== project ? ` ${i18n.t('chat:fileChips.inProject', { project: own })}` : '');

  const blocks = await Promise.all(
    tokens.map(async (m) => {
      const commitParsed = parseCommitToken(m[0]);
      if (commitParsed) {
        return i18n.t('chat:fileChips.commitRef', commitParsed) + foreign(commitParsed.project);
      }

      const docRefParsed = parseDocRefToken(m[0]);
      if (docRefParsed) {
        return i18n.t('chat:fileChips.docRef', { title: docRefParsed.title, id: docRefParsed.id });
      }

      const docParsed = parseDocToken(m[0]);
      if (docParsed) {
        try {
          const doc = await documentsApi.fetchById(docParsed.id);
          const title = doc?.title ?? docParsed.title;
          const description = doc?.description ?? '';
          return `\n\n${i18n.t('chat:fileChips.docHeader', { title, id: docParsed.id })}\n${description}\n`;
        } catch {
          return `\n\n${i18n.t('chat:fileChips.docLoadFailed', { id: docParsed.id })}\n`;
        }
      }

      const parsed = parseToken(m[0]);
      if (!parsed) return m[0];
      const { path, from, to, refOnly } = parsed;
      const where = foreign(parsed.project);

      if (refOnly) return `\`${path}\`${where}`;

      try {
        const data = await fetchContent(path, { from, to, project: parsed.project || project });
        const range = from != null && to != null ? ` (${from}–${to})` : '';
        if (data?.binary) return `\n\n\`${path}\`${range}${where}: ${i18n.t('chat:fileChips.binaryFile')}\n`;
        const content = data?.content ?? '';
        const fence = fenceFor(content);
        const lang = data?.language || '';
        return `\n\n\`${path}\`${range}${where}:\n${fence}${lang}\n${content}\n${fence}\n`;
      } catch {
        return `\n\n\`${path}\`${where}: ${i18n.t('chat:fileChips.readFailed')}\n`;
      }
    }),
  );

  let i = 0;
  return text.replace(TOKEN_RE, () => blocks[i++]);
}
