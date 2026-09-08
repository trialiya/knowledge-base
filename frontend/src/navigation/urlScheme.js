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

/**
 * Полный адрес чата: путь плюс запрос find-бара, если сюда пришли из поиска.
 *
 * Запрос — состояние экрана, а не часть идентичности чата, но живёт именно в
 * адресе: ссылкой на найденное сообщение делятся, и Ctrl+клик с карточки
 * результата обязан открыть чат с той же подсветкой, что была в выдаче.
 */
export function chatUrl(chatId, { find } = {}) {
  const p = new URLSearchParams();
  if (find) p.set('find', find);
  const qs = p.toString();
  return chatPath(chatId) + (qs ? `?${qs}` : '');
}

/** `/knowledge/doc/<id>` — документ или папка базы знаний. */
export function docPath(docId) {
  return `/knowledge/doc/${encodeURIComponent(docId)}`;
}

/** `/knowledge` — база знаний без выбранного ресурса. */
export const KNOWLEDGE_PATH = '/knowledge';

/** `/knowledge/search` — результаты поиска по базе знаний (запрос и режим уходят в query). */
export const KB_SEARCH_PATH = '/knowledge/search';

/**
 * `/search` — единый поиск: файлы, документы и чаты в одном разделе.
 * Запрос, выбранная категория и её фильтры уходят в query (см. useAppNavigation).
 */
export const SEARCH_PATH = '/search';

/** `/files` | `/files/<path…>` — путь файла лежит в самом пути. */
export function filesPath(path) {
  const encoded = encodeFilePath(path);
  return encoded ? `/files/${encoded}` : '/files';
}

/**
 * Полный адрес файла со ссылки: путь плюс проект, если он не дефолтный.
 *
 * Проект — в query, хотя это и часть идентичности ресурса: сегментом его от
 * каталога репозитория не отличить (`/files/docs/...` — это проект `docs` или
 * папка `docs`?), а разбирать URL, дожидаясь списка проектов, значит не уметь
 * прочитать адрес синхронно. В той же форме проект стоит и в markdown-ссылках,
 * которые пишет модель (`/files?path=…&project=…`).
 *
 * Дефолтный проект не пишем — как и любое значение по умолчанию в этой схеме;
 * адрес без проекта означает именно его. Так же и с ревизией: пусто — рабочее
 * дерево.
 *
 * Всё, кроме пути и проекта, — состояние экрана, и его собирает объект: у него
 * читаемое имя на месте вызова, а список позиционных «путь, проект, ревизия,
 * запрос, регулярка» просчитывается только по этому файлу.
 */
export function filesUrl(path, project, { rev, find, findRegex } = {}) {
  const p = new URLSearchParams();
  if (project) p.set('project', project);
  // Ревизия — тоже часть адреса файла: ссылка на совпадение, найденное в снимке
  // коммита, обязана открыть файл в том же снимке, а не в рабочем дереве.
  if (rev) p.set('rev', rev);
  // Запрос, по которому файл нашёлся: он и подсветится в открытом файле. В
  // адресе, а не в переходе, — иначе Ctrl+клик и перезагрузка теряли бы его.
  if (find) p.set('find', find);
  if (find && findRegex) p.set('re', '1');
  const qs = p.toString();
  return filesPath(path) + (qs ? `?${qs}` : '');
}
