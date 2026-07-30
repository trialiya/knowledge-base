/**
 * ──────────────────────────────────────────────────────────────────────────
 * urlScheme — построение канонических адресов приложения.
 * ──────────────────────────────────────────────────────────────────────────
 *
 * Схему URL целиком описывает useAppNavigation (он же — единственный, кто пишет
 * в window.history). Здесь живёт ровно одна её часть: как из ресурса собрать
 * путь. Отдельный модуль нужен потому, что путь строит не только навигация:
 * ссылки в разметке (doc-ссылки документов и сообщений чата, карточки
 * результатов поиска) обязаны иметь НАСТОЯЩИЙ href в актуальной схеме — иначе
 * средняя кнопка мыши и Ctrl/Cmd-клик открывают в новой вкладке устаревший
 * адрес. Одно место = адреса не разъезжаются.
 *
 * Хранимая форма doc-ссылки внутри markdown остаётся прежней — `/?doc=ID`
 * (её пишет бэкенд, см. DocumentLinkRewriter, и модель по системному промпту).
 * Разбирает обе формы docLinkParsing.js; здесь — только канонический вывод.
 */

/** Декодировать сегмент пути, не падая на битом percent-encoding. */
export function decodeSegment(seg) {
  try {
    return decodeURIComponent(seg);
  } catch {
    return seg;
  }
}

/** Путь файла → сегменты URL ('a/b c.md' → 'a/b%20c.md'). */
export function encodeFilePath(path) {
  return String(path || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/');
}

/** Сегменты URL → путь файла ('a/b%20c.md' → 'a/b c.md'). */
export function decodeFilePath(encoded) {
  return String(encoded || '')
    .split('/')
    .filter(Boolean)
    .map(decodeSegment)
    .join('/');
}

/** `/chat` | `/chat/<id>` ('new' — черновик). */
export function chatPath(chatId) {
  return chatId ? `/chat/${encodeURIComponent(chatId)}` : '/chat';
}

/** `/knowledge/doc/<id>` — документ или папка базы знаний. */
export function docPath(docId) {
  return `/knowledge/doc/${encodeURIComponent(docId)}`;
}

/** `/knowledge` — база знаний без выбранного ресурса. */
export const KNOWLEDGE_PATH = '/knowledge';

/** `/knowledge/search` — результаты поиска (запрос и режим уходят в query). */
export const SEARCH_PATH = '/knowledge/search';

/** `/files` | `/files/<path…>` — путь файла лежит в самом пути. */
export function filesPath(path) {
  const encoded = encodeFilePath(path);
  return encoded ? `/files/${encoded}` : '/files';
}
