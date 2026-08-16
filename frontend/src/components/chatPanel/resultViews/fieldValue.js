// Значение поля ответа → строка для показа. Общее для видов, которые печатают
// произвольные поля DTO: список записей, дерево, шапки.

import { formatFileSize } from '../../../utils/formatting';

// Только настоящие байты: `chars` — символы, и «210 B» на них было бы просто
// неверной единицей.
const SIZE_KEYS = new Set(['fileSize', 'sizeBytes', 'size']);

// Дата опознаётся по виду значения, а не по имени поля: у MCP-инструментов
// поле может называться как угодно, а ISO-8601 остаётся ISO-8601.
const ISO_DATE = /^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}|$)/;

const isPlainObject = (value) => !!value && typeof value === 'object' && !Array.isArray(value);

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
  if (typeof value === 'string' && ISO_DATE.test(value)) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) return date.toLocaleString(locale);
  }
  return String(value);
};
