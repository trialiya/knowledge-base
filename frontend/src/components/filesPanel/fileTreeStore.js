// ─── Кэш файлового дерева, переживающий размонтирование панели ──────────────
// FilesPanel монтируется только пока открыт раздел «Файлы» (см. App.js), а
// вместе с ним умирало и состояние useFileTree — возврат в раздел заново
// выкачивал всю цепочку каталогов до открытого файла. Кэш вынесен в модуль:
// дерево при возврате рисуется мгновенно, а запрос уходит только за тем, чего
// в нём нет.
//
// Данные из git, а репозиторий живёт своей жизнью (коммиты, правки файловыми
// инструментами), поэтому листинги не вечны: если к ним не обращались дольше
// TTL, кэш каталогов сбрасывается — внутри же одного «сеанса просмотра»
// листинги переиспользуются, как и раньше при жизни одного монтирования.
//
// Раскрытые каталоги (expanded) TTL не подчиняются — это память о том, что
// пользователь открыл, а не данные из git, протухать ей нечем. Важно и то, что
// readDir (в отличие от readDirs/readExpanded) зовётся не только при
// монтировании панели, а и посреди сессии — из ensureDir и из эффекта
// открытия пути. Если бы протухание там же стирало expanded, набор молча
// разошёлся бы с dirs: вызвавший readDir код тут же перезаписывает dirs через
// putDirs, а expanded остаётся пустым, потому что локальный expanded-стейт
// компонента (уже верный, ведь ничего в нём не менялось) не увидел повода
// сходить в putExpanded.

const TTL_MS = 60_000;

const store = {
  dirs: new Map(), // dirPath → GitFileNode[]
  expanded: new Set(['']),
  at: 0, // время последней записи листингов
};

function expireIfStale() {
  if (store.at && Date.now() - store.at > TTL_MS) {
    store.dirs.clear();
    store.at = 0;
  }
}

/** Снимок кэша листингов в виде объекта, каким его ждёт дерево: dirPath → nodes. */
export function readDirs() {
  expireIfStale();
  return Object.fromEntries(store.dirs);
}

/**
 * Листинг одного каталога (undefined — его в кэше нет). Отвечает на «нужно ли
 * запрашивать этот каталог» без снимка всего кэша: спрашивают из колбэков и
 * эффектов, где состояние компонента было бы лишним зеркалом.
 */
export function readDir(dir) {
  expireIfStale();
  return store.dirs.get(dir);
}

/** Раскрытые каталоги на момент прошлого визита в раздел. */
export function readExpanded() {
  expireIfStale();
  return new Set(store.expanded);
}

/** Кладёт в кэш листинги нескольких каталогов: { dirPath: nodes }. */
export function putDirs(entries) {
  for (const [dir, nodes] of Object.entries(entries)) {
    store.dirs.set(dir, nodes);
  }
  store.at = Date.now();
}

export function putExpanded(expanded) {
  store.expanded = new Set(expanded);
}

/** Только для тестов: очистить кэш между кейсами (модуль живёт дольше рендера). */
export function resetFileTreeCache() {
  store.dirs.clear();
  store.expanded = new Set(['']);
  store.at = 0;
}

/** Каталоги-предки пути (от корня), сам путь не включается. */
export function ancestorsOf(path) {
  const dirs = [''];
  if (!path) return dirs;
  for (let slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
    dirs.push(path.slice(0, slash));
  }
  return dirs;
}

/**
 * Сбрасывает из кэша листинги каталога-предка изменённого пути И всех ЕГО
 * предков — правка файлового инструмента (createFile/editFile) из чата может
 * как добавить файл в уже известный каталог, так и завести новый вложенный
 * каталог, которого раньше не было в листинге его родителя. Сам путь `path`
 * (не будучи каталогом) в кэше не лежит — трогать нечего.
 */
export function invalidatePath(path) {
  ancestorsOf(path).forEach((dir) => store.dirs.delete(dir));
}
