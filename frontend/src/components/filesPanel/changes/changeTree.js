/**
 * Список изменённых путей → иерархия каталогов для режима «Изменения».
 *
 * Дерево строится из самих путей, а не запрашивается у бэкенда: в списке
 * изменений каталог интересен только как общий префикс нескольких файлов, и
 * его содержимое целиком (то, что отдаёт /api/git/tree) здесь показывать
 * нечего — большинство соседей не менялось.
 *
 * Каталог с единственным потомком-каталогом склеивается с ним в одну строку
 * (`src/main/java` вместо трёх ступеней подряд): цепочки из одного элемента
 * тратят ширину панели, ничего не разделяя.
 */

/**
 * Плоская раскладка списка: те же записи, упорядоченные по имени файла.
 *
 * Порядок бэкенда (отслеживаемые — как их выдал diff, неотслеживаемые — по
 * пути) читается только в иерархии, где каталог сам собирает соседей; в
 * плоском перечне каталог у каждой строки свой, и без сортировки одноимённые
 * файлы из разных мест разъезжаются по списку. Имя — то, что стоит в строке
 * первым, каталог лишь разводит совпадения.
 *
 * @param {Array} entries GitDiffEntry[]
 * @returns {Array} новый массив: исходный принадлежит вызывающему
 */
export function sortByName(entries) {
  const nameOf = (entry) => entry.path.split('/').pop();
  return [...entries].sort((a, b) => nameOf(a).localeCompare(nameOf(b)) || a.path.localeCompare(b.path));
}

/**
 * @param {Array} entries GitDiffEntry[]
 * @returns {Array} узлы верхнего уровня: { type: 'dir', path, name, children } | { type: 'file', path, name, entry }
 */
export function buildChangeTree(entries) {
  // Промежуточная форма: каталог хранит потомков картой, чтобы путь ложился в
  // неё за один проход, без поиска по массиву на каждый сегмент.
  const root = { dirs: new Map(), files: [] };

  for (const entry of entries) {
    const segments = entry.path.split('/');
    const fileName = segments.pop();
    let node = root;
    let prefix = '';
    for (const segment of segments) {
      prefix = prefix ? `${prefix}/${segment}` : segment;
      let child = node.dirs.get(segment);
      if (!child) {
        child = { dirs: new Map(), files: [], path: prefix, name: segment };
        node.dirs.set(segment, child);
      }
      node = child;
    }
    node.files.push({ type: 'file', path: entry.path, name: fileName, entry });
  }

  return toNodes(root);
}

/** Промежуточный узел → отсортированные потомки: каталоги перед файлами. */
function toNodes(node) {
  const dirs = [...node.dirs.values()]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((dir) => {
      const collapsed = collapse(dir);
      return { type: 'dir', path: collapsed.path, name: collapsed.name, children: toNodes(collapsed) };
    });
  // Файлы приходят в порядке бэкенда (отслеживаемые в порядке diff'а,
  // неотслеживаемые по алфавиту) — внутри каталога он и остаётся.
  return [...dirs, ...node.files];
}

/** Склеить цепочку каталогов с одним потомком-каталогом и без своих файлов. */
function collapse(dir) {
  let current = dir;
  let name = dir.name;
  while (current.files.length === 0 && current.dirs.size === 1) {
    const only = [...current.dirs.values()][0];
    name = `${name}/${only.name}`;
    current = only;
  }
  return { ...current, name };
}
