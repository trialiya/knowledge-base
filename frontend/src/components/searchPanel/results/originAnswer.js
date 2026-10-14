// Ключ запроса «когда появилось» для useLineOrigin. Разбор самого ответа —
// общий с чатом, в common/git/lineOrigin.js.

/**
 * Ключ запроса: всё, от чего зависит ответ. Тот же файл и строка в другой
 * ревизии или под другим запросом — другой ответ.
 */
export const originKey = ({ path, line, query, rev, project }) =>
  JSON.stringify([project ?? '', rev ?? '', path, line, query]);
