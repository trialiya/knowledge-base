import React from 'react';

/**
 * Подсветка совпадений поискового запроса в результатах (FileSearch,
 * FilePickerDropdown). Логика матчинга зеркалит бэкенд:
 *  - файлы (GitService.fuzzyScore) — подпоследовательность без учёта регистра,
 *    сначала по имени, при неудаче — по полному пути;
 *  - документы — подстрока в названии.
 * Здесь сначала пробуем непрерывную подстроку (наиболее ожидаемая пользователем
 * подсветка), и только если её нет — жадную подпоследовательность слева направо.
 */

/**
 * Индексы символов text, совпавших с query (без учёта регистра).
 * @returns {number[]|null} отсортированные индексы или null, если query пуст
 *   либо не является подпоследовательностью text
 */
export function matchIndices(text, query) {
  const q = (query ?? '').trim().toLowerCase();
  if (!q || !text) return null;
  const lower = text.toLowerCase();

  const at = lower.indexOf(q);
  if (at >= 0) return Array.from({ length: q.length }, (_, i) => at + i);

  const indices = [];
  let qi = 0;
  for (let ti = 0; ti < lower.length && qi < q.length; ti++) {
    if (lower[ti] === q[qi]) {
      indices.push(ti);
      qi++;
    }
  }
  return qi === q.length ? indices : null;
}

/**
 * Разбить text на чередование строк и <mark class="search-match"> по индексам
 * совпавших символов (соседние индексы сливаются в один <mark>).
 * @returns {React.ReactNode} исходная строка, если индексов нет
 */
export function renderHighlighted(text, indices) {
  if (!indices || indices.length === 0) return text;
  const set = new Set(indices);
  const nodes = [];
  let start = 0;
  let marked = set.has(0);
  for (let i = 1; i <= text.length; i++) {
    const m = i < text.length && set.has(i);
    if (i === text.length || m !== marked) {
      const chunk = text.slice(start, i);
      nodes.push(
        marked ? (
          <mark key={start} className="search-match">
            {chunk}
          </mark>
        ) : (
          chunk
        ),
      );
      start = i;
      marked = m;
    }
  }
  return nodes;
}

/** Подсветить совпадения query в text (или вернуть text без изменений). */
export default function highlightMatch(text, query) {
  return renderHighlighted(text, matchIndices(text, query));
}

/**
 * Подсветка результата поиска файла, где имя и каталог рендерятся отдельными
 * строками. Как на бэкенде: сначала матчим имя; если имя не совпало — матчим
 * полный путь и раскладываем совпавшие индексы на части dir и name.
 * @returns {{ name: React.ReactNode, dir: React.ReactNode }}
 */
export function highlightFileMatch(name, path, query) {
  const dir = path.length > name.length ? path.slice(0, path.length - name.length - 1) : '';

  const nameIdx = matchIndices(name, query);
  if (nameIdx) return { name: renderHighlighted(name, nameIdx), dir };

  const pathIdx = matchIndices(path, query);
  if (!pathIdx) return { name, dir };

  const nameStart = path.length - name.length;
  const dirIdx = pathIdx.filter((i) => i < dir.length);
  const nIdx = pathIdx.filter((i) => i >= nameStart).map((i) => i - nameStart);
  return { name: renderHighlighted(name, nIdx), dir: renderHighlighted(dir, dirIdx) };
}
