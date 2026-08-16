// Значение поля ответа → строка для показа. Общее для видов, которые печатают
// произвольные поля DTO: список записей, дерево, шапки.

import { formatFileSize } from '../../../utils/formatting';

// Только настоящие байты: `chars` — символы, и «210 B» на них было бы просто
// неверной единицей.
const SIZE_KEYS = new Set(['fileSize', 'sizeBytes', 'size', 'bytesRead']);

// Дата опознаётся по виду значения, а не по имени поля: у MCP-инструментов
// поле может называться как угодно, а ISO-8601 остаётся ISO-8601.
const ISO_DATE_TIME = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/;
const ISO_DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

/**
 * Дата без времени → локальная полночь.
 *
 * `new Date('2026-07-13')` — это полночь UTC, и западнее Гринвича она печатается
 * предыдущим днём, да ещё и с выдуманным временем. Собираем дату по частям и
 * сверяем результат: так `2026-13-45` останется строкой, а не перекатится в
 * следующий год молча.
 */
const localDate = (value) => {
  const parts = ISO_DATE_ONLY.exec(value);
  if (!parts) return null;

  const [, year, month, day] = parts.map(Number);
  const date = new Date(year, month - 1, day);
  // Конструктор трактует год до сотни как 19xx; `setFullYear` возвращает тот,
  // что стоял в строке, не трогая месяц и день.
  date.setFullYear(year);
  return date.getMonth() === month - 1 && date.getDate() === day ? date : null;
};

/** Значение поля → строка. Вложенное сворачивается, а не прячется. */
export const formatFieldValue = (key, value, locale) => {
  if (Array.isArray(value)) {
    // Хлебные крошки (`parentList`) и подобные списки объектов читаются по
    // названиям, а не по JSON.
    return value
      .map((item) => (isPlainObject(item) ? item.title ?? item.name ?? JSON.stringify(item) : String(item)))
      .join(' / ');
  }
  if (isPlainObject(value)) return JSON.stringify(value);
  if (typeof value === 'number' && SIZE_KEYS.has(key)) return formatFileSize(value);
  if (typeof value === 'string') {
    // Без времени печатаем только дату: 00:00 в строке — артефакт разбора, а не
    // то, что стояло в ответе.
    const dateOnly = localDate(value);
    if (dateOnly) return dateOnly.toLocaleDateString(locale);

    if (ISO_DATE_TIME.test(value)) {
      const date = new Date(value);
      if (!Number.isNaN(date.getTime())) return date.toLocaleString(locale);
    }
  }
  return String(value);
};
