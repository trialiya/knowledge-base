// Режим «JSON» в модалке вызова инструмента: форматирование и подсветка.
// Вынесено из компонента — чистые функции над строкой, без React.

/** Красиво отформатированный JSON, либо исходная строка, если это не JSON. */
export const formatJson = (raw) => {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

/** То же, но null вместо исходной строки — когда важно отличить «не JSON». */
export const tryFormatJson = (raw) => {
  if (!raw) return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return null;
  }
};

const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * Подсветка JSON: строка → HTML для dangerouslySetInnerHTML.
 *
 * Экранирование идёт ДО расстановки тегов — иначе `<` из значения превратился бы
 * в разметку. Классы блока `json-highlight` описаны в tool-call-detail.css.
 */
export const highlightJson = (text) => {
  const escaped = escapeHtml(text);
  return escaped.replace(
    /("(?:\\u[0-9a-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (match) => {
      let cls;
      if (match.startsWith('"')) {
        cls = match.endsWith(':') ? 'key' : 'string';
      } else if (match === 'true' || match === 'false') {
        cls = 'boolean';
      } else if (match === 'null') {
        cls = 'null';
      } else {
        cls = 'number';
      }
      return `<span class="json-highlight__${cls}">${match}</span>`;
    },
  );
};
