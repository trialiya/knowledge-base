// Что режим «Обзор» показывает для формы «список однотипных записей»: выдача
// поиска, вложения, файлы репозитория, коммиты. Строка на запись, полный набор
// полей — по развороту.
//
// Разбор — по форме, а не по имени инструмента (см. registry.js). Признак
// формы: массив плоских объектов с общим набором ключей (см. `sameShape`).

import { contentTakesArray, isPlainObject } from './contentResult';

// Выше этого числа записей вид не берётся вовсе: столько строк не читают, а
// разворачивать их по одной — не тот инструмент. Показ ограничен отдельно.
const MAX_RECORDS = 500;

/** Чем подписана строка, в порядке предпочтения. */
const TITLE_FIELDS = ['title', 'path', 'fileName', 'name', 'message'];

/** Короткое пояснение под заголовком. */
const SUBTITLE_FIELDS = ['snippet', 'summary', 'description', 'text'];

// Чипы правой части строки: по одному на смысл, первое присутствующее поле
// слота. У записи бывают и createdAt, и updatedAt, и в строке они встали бы
// двумя одинаковыми датами; полный набор всё равно раскрывается под строкой,
// поэтому «не угадали с чипом» не значит «спрятали данные».
const META_SLOTS = [
  ['type', 'contentType', 'kind'],
  ['status'],
  ['shortHash'],
  ['author'],
  ['matchLine'],
  ['fileSize', 'sizeBytes', 'size'],
  ['date', 'updatedAt', 'createdAt'],
];

const firstField = (obj, fields) => {
  for (const field of fields) {
    const value = obj[field];
    if (typeof value === 'string' && value.trim()) return { field, value };
  }
  return null;
};

/**
 * Нечего показывать. Пустая коллекция — тоже нечего: `findDocumentsByName`
 * кладёт `children: []` в каждую запись, и без этой проверки в развороте стояла
 * бы строка «children» с пустотой справа.
 */
const isEmpty = (value) =>
  value === null ||
  value === undefined ||
  value === '' ||
  (Array.isArray(value) && value.length === 0) ||
  (isPlainObject(value) && Object.keys(value).length === 0);

/**
 * Записи одного DTO: у всех одни и те же ключи, кроме необязательных — Jackson
 * не печатает `null` у полей с `@JsonInclude(NON_NULL)` (`oldPath` есть только
 * у переименования, `body` — только у коммита с телом). Отсутствующий ключ
 * равен `null`, поэтому разниться наборам разрешено, но общие для всех ключи
 * должны быть большинством: у случайного массива объектов их почти нет.
 */
const sameShape = (objects) => {
  const union = new Set(objects.flatMap(Object.keys));
  const common = [...union].filter((key) => objects.every((obj) => Object.hasOwn(obj, key)));
  return common.length * 2 > union.size;
};

const toRecord = (obj, key) => {
  const title = firstField(obj, TITLE_FIELDS);
  const subtitle = firstField(obj, SUBTITLE_FIELDS);
  const shown = new Set([title?.field, subtitle?.field]);

  return {
    key,
    title: title?.value ?? null,
    subtitle: subtitle?.value ?? null,
    meta: META_SLOTS.map((slot) => slot.find((field) => !shown.has(field) && !isEmpty(obj[field])))
      .filter(Boolean)
      .map((field) => ({ key: field, value: obj[field] })),
    // Всё остальное — по развороту. Заголовок и пояснение оттуда убраны: они
    // стоят строкой выше, дословно.
    fields: Object.keys(obj)
      .filter((field) => !shown.has(field) && !isEmpty(obj[field]))
      .map((field) => ({ key: field, value: obj[field] })),
  };
};

/**
 * Разобранный ответ вызова → записи для `<RecordListView>`, либо null.
 *
 * Список текстов сюда не попадает — его показывает `content`; границу задаёт
 * `contentTakesArray`, то есть сам разбор соседнего вида.
 */
export const detectRecordList = ({ parsed, isJson }) => {
  if (!isJson || !Array.isArray(parsed)) return null;
  if (parsed.length === 0 || parsed.length > MAX_RECORDS) return null;
  if (!parsed.every(isPlainObject)) return null;

  if (!sameShape(parsed)) return null;
  // Уступаем ровно то, что `content` действительно возьмёт: спрашиваем у него,
  // а не описываем его правила второй раз. Разойдись эти два описания — на
  // спорной форме отказались бы оба вида сразу, и выдача провалилась бы в сырой
  // JSON, то есть ровно в ту дыру, которую весь режим и закрывает.
  if (contentTakesArray(parsed)) return null;

  const records = parsed.map((record, i) => toRecord(record, `record-${i}`));
  // Запись, у которой нечего показать в строке, — форма не та: получился бы
  // столбец пустых кнопок.
  return records.every((record) => record.title) ? records : null;
};
