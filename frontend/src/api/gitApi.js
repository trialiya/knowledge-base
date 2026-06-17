// ─── Git API ───────────────────────────────────────────────────────────────
// Тонкие обёртки вокруг /api/git/* — поиск файлов репозитория для автодополнения
// в композере чата и чтение содержимого (превью/разворачивание чипа при отправке).

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
};

export default gitApi;
