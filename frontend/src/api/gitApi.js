// ─── Git API ───────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/git/* — поиск файлов репозитория для автодополнения
// в композере чата, чтение содержимого (превью/разворачивание чипа при отправке)
// и история коммитов для вкладки «Инфо» файлового браузера.

import { request } from './client';

const gitApi = {
  /**
   * Fuzzy-поиск трекаемых файлов по имени: q='mgi' → MessageInput.
   * Возвращает GitFileNode[] { path, name, type, size }.
   */
  searchFiles: (q, limit = 10, signal) => {
    const params = new URLSearchParams({ q, limit: String(limit) });
    return request(`/api/git/files/search?${params}`, signal ? { signal } : undefined);
  },

  /**
   * Содержимое файла (опц. диапазон строк, 1-based включительно).
   * Возвращает GitFileContent { path, content, binary, sizeBytes, language, totalLines, ... }.
   */
  getFileContent: (path, from, to, signal) => {
    const params = new URLSearchParams({ path });
    if (from != null) params.set('from', String(from));
    if (to != null) params.set('to', String(to));
    return request(`/api/git/files/content?${params}`, signal ? { signal } : undefined);
  },

  /**
   * Прямые потомки каталога (файлы + подкаталоги) для дерева файлового браузера.
   * path='' или omitted — корень репозитория. Возвращает GitFileNode[], каталоги
   * отсортированы перед файлами, затем по алфавиту.
   */
  getTree: (path, signal) => {
    const qs = path ? `?${new URLSearchParams({ path })}` : '';
    return request(`/api/git/tree${qs}`, signal ? { signal } : undefined);
  },

  /**
   * История коммитов (свежие первыми), опционально по одному пути.
   * path='' или omitted — история всего репозитория. Возвращает GitCommit[]
   * { hash, shortHash, author, email, date, message }.
   */
  getCommits: (path, limit = 20, signal) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (path) params.set('path', path);
    return request(`/api/git/commits?${params}`, signal ? { signal } : undefined);
  },
};

export default gitApi;
