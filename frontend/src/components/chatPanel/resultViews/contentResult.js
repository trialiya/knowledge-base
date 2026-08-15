// Что режим «Обзор» в модалке вызова инструмента показывает вместо JSON, когда
// инструмент вернул текст: содержимое файла, вложения, документа или его секции.
//
// Разбор идёт по ФОРМЕ ответа, а не по имени инструмента. Перечислять имена
// бесполезно: инструменты MCP-серверов приходят снаружи, их набор заранее
// неизвестен, и любой новый @Tool пришлось бы дописывать сюда руками. Вместо
// этого ответ проходит по форме — есть длинное текстовое поле и нет вложенной
// коллекции — и попадает в этот вид сам.
//
// Всё, что формой не подошло, остаётся в JSON-режиме: у списков, деревьев и
// diff'ов свои виды, и лучше показать честный JSON, чем выдрать из ответа одно
// поле и умолчать про остальные.

/** Поля, в которых инструменты возвращают собственно текст. */
const TEXT_FIELDS = ['content', 'description', 'text', 'report'];

/** Чем подписан блок — путь файла, имя вложения или заголовок документа. */
const TITLE_FIELDS = ['path', 'fileName', 'title'];

// Вложенная коллекция — признак другой формы: дерево документов, список
// коммитов с файлами, пачка правок скрипта. Их показывает не этот вид.
const COLLECTION_FIELDS = ['children', 'files', 'sections', 'symbols', 'edits', 'parentList', 'items', 'log'];

// Короткая однострочная строка — это значение, а не содержимое: `getChatId` и
// `recordChatInsights` в текстовом вьювере смотрелись бы нелепо.
const MIN_TEXT_LEN = 200;

// Больше двух десятков текстов за вызов — это уже не «содержимое», а выдача:
// такую лучше показать списком, когда он появится.
const MAX_ITEMS = 20;

// Факты в шапке блока, в порядке вывода. Значения — как есть; форматирует их
// компонент (размер — через formatFileSize, подписи — через i18n).
const FACT_FIELDS = ['language', 'contentType', 'type', 'lineCount', 'sizeBytes', 'fileSize', 'descriptionVersion'];

const EXT_LANGUAGE = {
  md: 'markdown',
  mdx: 'markdown',
  java: 'java',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  py: 'python',
  sql: 'sql',
  json: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'xml',
  css: 'css',
  html: 'html',
  sh: 'shell',
  gradle: 'groovy',
  properties: 'properties',
};

const languageFromTitle = (title) => {
  const ext = /\.([a-z0-9]+)$/i.exec(title || '')?.[1]?.toLowerCase();
  return ext ? EXT_LANGUAGE[ext] ?? null : null;
};

const firstString = (obj, fields) => {
  for (const field of fields) {
    const value = obj[field];
    if (typeof value === 'string' && value.trim()) return { field, value };
  }
  return null;
};

const hasCollection = (obj) => COLLECTION_FIELDS.some((field) => Array.isArray(obj[field]) && obj[field].length > 0);

/** Текст достаточно длинный или многострочный, чтобы его стоило показывать блоком. */
const isContentText = (text) => text.includes('\n') || text.length >= MIN_TEXT_LEN;

const factsOf = (obj, skipField) =>
  FACT_FIELDS.filter((key) => key !== skipField)
    .map((key) => ({ key, value: obj[key] }))
    .filter(({ value }) => value !== null && value !== undefined && value !== '');

/**
 * Один объект ответа → блок текста, либо null если форма не та.
 *
 * `binary: true` — тоже валидный блок: показать нечего, но сказать об этом
 * нужно, иначе «Обзор» просто исчезнет и пользователь решит, что вид сломан.
 */
const toItem = (obj, key) => {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;

  const title = firstString(obj, TITLE_FIELDS)?.value ?? null;

  if (obj.binary === true) {
    return { key, title, binary: true, text: null, language: null, startLine: 1, markdown: false, facts: factsOf(obj) };
  }
  if (hasCollection(obj)) return null;

  const text = firstString(obj, TEXT_FIELDS);
  if (!text || !isContentText(text.value)) return null;

  const language = (typeof obj.language === 'string' && obj.language) || languageFromTitle(title);
  return {
    key,
    title,
    binary: false,
    text: text.value,
    language,
    // fromLine приходит от getFileContent, когда прочитан диапазон, а не весь файл:
    // без него номера строк врали бы на величину смещения.
    startLine: Number.isInteger(obj.fromLine) && obj.fromLine > 0 ? obj.fromLine : 1,
    // descriptionVersion есть только у документов базы знаний — они markdown по определению.
    markdown: language === 'markdown' || obj.descriptionVersion !== undefined,
    facts: factsOf(obj, text.field),
  };
};

/** Голая строка в ответе (`getAttachmentContent`) — блок без метаданных. */
const bareTextItem = (text, title) =>
  isContentText(text)
    ? {
        key: 'text',
        title: title ?? null,
        binary: false,
        text,
        language: languageFromTitle(title),
        startLine: 1,
        markdown: languageFromTitle(title) === 'markdown',
        facts: [],
      }
    : null;

const titleFromArgs = (argumentsRaw) => {
  if (typeof argumentsRaw !== 'string' || !argumentsRaw) return null;
  try {
    const args = JSON.parse(argumentsRaw);
    return args && typeof args === 'object' ? firstString(args, TITLE_FIELDS)?.value ?? null : null;
  } catch {
    return null;
  }
};

/**
 * `resultText` вызова → блоки для режима «Обзор», либо null — тогда модалка
 * показывает только JSON и переключателя режимов не будет вовсе.
 *
 * `argumentsRaw` нужен ровно для одного: подписать блок, когда сам ответ —
 * голая строка и имени файла в нём нет (`getAttachmentContent` отдаёт текст
 * вложения, а его имя осталось в аргументах вызова).
 */
export const detectContentResult = (resultText, argumentsRaw) => {
  if (typeof resultText !== 'string' || !resultText) return null;

  let parsed;
  try {
    parsed = JSON.parse(resultText);
  } catch {
    // Не JSON вовсе — значит, инструмент вернул сырой текст.
    const item = bareTextItem(resultText, titleFromArgs(argumentsRaw));
    return item ? [item] : null;
  }

  if (typeof parsed === 'string') {
    const item = bareTextItem(parsed, titleFromArgs(argumentsRaw));
    return item ? [item] : null;
  }

  if (Array.isArray(parsed)) {
    if (parsed.length === 0 || parsed.length > MAX_ITEMS) return null;
    const items = parsed.map((entry, i) => toItem(entry, `item-${i}`));
    // Все до одного: если хоть один элемент другой формы, список показывает JSON —
    // иначе часть выдачи молча пропала бы с экрана.
    return items.every(Boolean) ? items : null;
  }

  const item = toItem(parsed, 'item-0');
  return item ? [item] : null;
};
