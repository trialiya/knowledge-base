/** Имя файла — последний сегмент пути. */
const baseName = (path) => path.slice(path.lastIndexOf('/') + 1);

const norm = (s) => s.trim().toLowerCase();

/** Запрос назвал файл целиком: его имя или весь путь, без учёта регистра. */
export function isExactName(path, query) {
  const q = norm(query);
  return !!q && (norm(baseName(path)) === q || norm(path) === q);
}

/**
 * Склеивает находки по содержимому (`git grep`) и по имени (`searchFiles`) в один
 * ответ категории «Файлы».
 *
 * `/api/git/files/search` ищет подпоследовательность — для пикера в композере это
 * удобно, а рядом с выдачей по содержимому дало бы шум: «abc» совпал бы с любым
 * путём, где эти буквы стоят по порядку. Поэтому оставляем только те, где запрос
 * встречается подстрокой в имени или пути — как и в поиске по содержимому.
 *
 * Порядок: файл, названный запросом целиком, — первым; затем файлы с совпадениями
 * в тексте (у них имя, если совпало, отмечено `nameMatch`); в конце — файлы,
 * найденные только по имени. Файл в обоих списках остаётся одной карточкой.
 *
 * Без находок по имени возвращается сам `grep` — та же ссылка, а не копия.
 *
 * @param grep   ответ GitGrepResult { total, truncated, files }
 * @param names  ответ searchFiles: [GitFileNode]
 */
export default function mergeFileHits(grep, names, query) {
  const q = norm(query);
  const hits = (Array.isArray(names) ? names : []).filter(
    (n) => n.type !== 'DIRECTORY' && q && norm(n.path).includes(q),
  );
  if (hits.length === 0) return grep;

  const byPath = new Map(hits.map((n) => [n.path, n]));
  const content = grep.files.map((f) => ({
    ...f,
    nameMatch: byPath.has(f.path),
  }));
  const inContent = new Set(grep.files.map((f) => f.path));
  const nameOnly = hits
    .filter((n) => !inContent.has(n.path))
    .map((n) => ({
      path: n.path,
      tracked: n.tracked !== false,
      lines: [],
      nameMatch: true,
    }));

  const all = [...content, ...nameOnly];
  const exact = all.filter((f) => isExactName(f.path, query));
  const rest = all.filter((f) => !isExactName(f.path, query));

  return {
    ...grep,
    // Файл, найденный по имени, — одно совпадение: без него счётчик в шапке
    // и у категории не увидел бы находку без строк.
    total: grep.total + nameOnly.length,
    files: [...exact, ...rest],
  };
}
