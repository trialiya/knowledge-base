// ─── Git API ───────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/git/* — поиск файлов репозитория для автодополнения
// в композере чата, чтение содержимого (превью/разворачивание чипа при отправке)
// и история коммитов для вкладки «Инфо» файлового браузера.
//
// Адрес файла — это ПАРА (проект, путь), а не один путь: один и тот же
// `backend/build.gradle` есть в каждом репозитории. Проект необязателен и по
// умолчанию берётся первый в конфигурации — так работают и все ссылки,
// записанные до того, как проекты появились.
//
// Хвост у всех методов — один объект опций, а не набор позиционных аргументов:
// раньше там подряд шли limit/from/to/signal, и проект, вставленный в эту
// очередь, менялся бы местами с соседом без единой ошибки.

import { request } from './client';

/** Опции запроса → query + fetch-init. */
const opts = (params, project, signal) => {
  if (project) params.set('project', project);
  const qs = params.toString();
  return [qs ? `?${qs}` : '', signal ? { signal } : undefined];
};

const gitApi = {
  /**
   * Fuzzy-поиск трекаемых файлов по имени: q='mgi' → MessageInput.
   * Возвращает GitFileNode[] { path, name, type, size }.
   */
  searchFiles: (q, { limit = 10, project, signal } = {}) => {
    const [qs, init] = opts(new URLSearchParams({ q, limit: String(limit) }), project, signal);
    return request(`/api/git/files/search${qs}`, init);
  },

  /**
   * Содержимое файла (опц. диапазон строк, 1-based включительно).
   * Возвращает GitFileContent { path, content, binary, sizeBytes, language, totalLines, ... }.
   */
  getFileContent: (path, { from, to, project, signal } = {}) => {
    const params = new URLSearchParams({ path });
    if (from != null) params.set('from', String(from));
    if (to != null) params.set('to', String(to));
    const [qs, init] = opts(params, project, signal);
    return request(`/api/git/files/content${qs}`, init);
  },

  /**
   * Открыть путь в файловом браузере одним запросом: чем путь является, его
   * содержимое (файл) или листинг (каталог) и — при ancestors=true — листинги
   * всех каталогов-предков, чтобы дерево слева раскрылось до него без запроса
   * на каждый уровень вложенности.
   *
   * Возвращает GitPathView { path, type: 'file'|'directory'|null, file?,
   * nodes?, tree: [{ path, nodes }] }.
   *
   * @param {boolean} ancestors — false, если листинги предков уже в кэше клиента.
   */
  browse: (path, { ancestors = true, project, signal } = {}) => {
    const params = new URLSearchParams();
    if (path) params.set('path', path);
    if (!ancestors) params.set('ancestors', 'false');
    const [qs, init] = opts(params, project, signal);
    return request(`/api/git/browse${qs}`, init);
  },

  /**
   * Прямые потомки каталога (файлы + подкаталоги) для дерева файлового браузера.
   * path='' или omitted — корень репозитория. Возвращает GitFileNode[], каталоги
   * отсортированы перед файлами, затем по алфавиту.
   */
  getTree: (path, { project, signal } = {}) => {
    const params = new URLSearchParams();
    if (path) params.set('path', path);
    const [qs, init] = opts(params, project, signal);
    return request(`/api/git/tree${qs}`, init);
  },

  /**
   * История коммитов (свежие первыми), опционально по одному пути.
   * path='' или omitted — история всего репозитория. Возвращает GitCommit[]
   * { hash, shortHash, author, email, date, message }.
   */
  getCommits: (path, { limit = 20, project, signal } = {}) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (path) params.set('path', path);
    const [qs, init] = opts(params, project, signal);
    return request(`/api/git/commits${qs}`, init);
  },

  /**
   * Поиск коммитов по префиксу хэша или подстроке сообщения (свежие первыми).
   * Возвращает те же GitCommit[], что и getCommits.
   */
  searchCommits: (q, { limit = 10, project, signal } = {}) => {
    const [qs, init] = opts(new URLSearchParams({ q, limit: String(limit) }), project, signal);
    return request(`/api/git/commits/search${qs}`, init);
  },
};

export default gitApi;
