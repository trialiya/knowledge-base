// Что режим «Обзор» в модалке вызова инструмента показывает вместо JSON, когда
// инструмент вернул текст: содержимое файла, вложения, документа или его секции.
//
// Разбор идёт по ФОРМЕ ответа, а не по имени инструмента (см. registry.js) —
// есть длинное текстовое поле и нет вложенной коллекции, значит это текст.
//
// Вид самый широкий из всех, поэтому в реестре стоит последним: всё, что формой
// не подошло, остаётся в JSON-режиме, и лучше показать честный JSON, чем выдрать
// из ответа одно поле и умолчать про остальные.

/** Поля, в которых инструменты возвращают собственно текст. */
const TEXT_FIELDS = ['content', 'description', 'text', 'report'];

/** Чем подписан блок — путь файла, имя вложения или заголовок документа. */
const TITLE_FIELDS = ['path', 'fileName', 'title'];

// Вложенная коллекция — признак другой формы: список коммитов с файлами,
// оглавление, пачка правок скрипта. Их показывает не этот вид.
//
// `children` сюда не входит намеренно: `getDocument` отдаёт документ вместе со
// списком дочерних, и это соседи по дереву, а не содержимое — содержимое лежит
// в `description`. Отбой по `children` уносил бы «Обзор» у любого документа,
// у которого есть вложенные, то есть у большей части базы.
const COLLECTION_FIELDS = ['files', 'sections', 'symbols', 'edits', 'parentList', 'items', 'log'];

// Короткая однострочная строка — это значение, а не содержимое: `getChatId` и
// `recordChatInsights` в текстовом вьювере смотрелись бы нелепо.
const MIN_TEXT_LEN = 200;

// Больше двух десятков текстов за вызов — это уже не «содержимое», а выдача:
// такую лучше показать списком, когда он появится.
const MAX_ITEMS = 20;

// Факты в шапке блока, в порядке вывода. Значения — как есть; форматирует их
// компонент (размер — через formatFileSize, подписи — через i18n).
const FACT_FIELDS = [
  'project',
  'language',
  'contentType',
  'type',
  'lineCount',
  'sizeBytes',
  'fileSize',
  'descriptionVersion',
];

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

export const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

const firstString = (obj, fields) => {
  for (const field of fields) {
    const value = obj[field];
    if (typeof value === 'string' && value.trim()) return { field, value };
  }
  return null;
};

const hasCollection = (obj) => COLLECTION_FIELDS.some((field) => Array.isArray(obj[field]) && obj[field].length > 0);

/**
 * Текст достаточно длинный или многострочный, чтобы его стоило показывать блоком.
 *
 * Экспортируется ради `scalar`: тот берёт ровно дополнение — короткую
 * однострочную строку. Так границу между видами задаёт одно правило, а не два
 * порога, которые разъедутся при первой же правке.
 */
export const isContentText = (text) => text.includes('\n') || text.length >= MIN_TEXT_LEN;

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
  if (!isPlainObject(obj)) return null;

  const title = firstString(obj, TITLE_FIELDS)?.value ?? null;

  if (obj.binary === true) {
    return { key, title, binary: true, text: null, language: null, startLine: 1, markdown: false, facts: factsOf(obj) };
  }
  if (hasCollection(obj)) return null;
  // Совпадение grepContent: его text уже несёт собственную нумерацию
  // (`:85:` — совпадение, `-84-` — контекст), и гуттер с номерами от единицы
  // поверх неё врал бы. Список таких записей забирает `grepMatches` раньше по
  // реестру; отбой нужен для одиночного объекта той же формы.
  if (Number.isInteger(obj.matchLine)) return null;

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

/**
 * Массив ответа → блоки, либо null. Все до одного: если хоть один элемент
 * другой формы, список показывает JSON — иначе часть выдачи молча пропала бы
 * с экрана.
 */
const itemsOfArray = (parsed) => {
  if (parsed.length === 0 || parsed.length > MAX_ITEMS) return null;
  const items = parsed.map((entry, i) => toItem(entry, `item-${i}`));
  return items.every(Boolean) ? items : null;
};

/**
 * Возьмёт ли этот вид массив целиком.
 *
 * Экспортируется ради `recordList` и `tree`: они уступают ему список текстов
 * (`getAttachmentContentByFileName`), и уступать надо ровно то, что он примет.
 * Предиката «несёт длинный текст» для этого мало — вид отказывается ещё и от
 * длинных списков, и от записей с вложенной коллекцией, и от совпадений grep,
 * а отказ обоих видов сразу роняет выдачу в сырой JSON. Поэтому граница здесь
 * не описана второй раз, а спрошена у самого разбора.
 */
export const contentTakesArray = (parsed) => Array.isArray(parsed) && itemsOfArray(parsed) !== null;

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
 * Разобранный ответ вызова → блоки для режима «Обзор», либо null.
 *
 * `argumentsRaw` нужен ровно для одного: подписать блок, когда сам ответ —
 * голая строка и имени файла в нём нет (`getAttachmentContent` отдаёт текст
 * вложения, а его имя осталось в аргументах вызова).
 */
export const detectContentResult = ({ parsed, isJson, resultText, argumentsRaw }) => {
  if (!isJson) {
    // Не JSON вовсе — значит, инструмент вернул сырой текст.
    const item = bareTextItem(resultText, titleFromArgs(argumentsRaw));
    return item ? [item] : null;
  }

  if (typeof parsed === 'string') {
    const item = bareTextItem(parsed, titleFromArgs(argumentsRaw));
    return item ? [item] : null;
  }

  if (Array.isArray(parsed)) return itemsOfArray(parsed);

  const item = toItem(parsed, 'item-0');
  return item ? [item] : null;
};
