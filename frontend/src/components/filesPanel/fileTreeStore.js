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

// Кэш — по проекту, а не один на всех: путь `backend/build.gradle` есть в каждом
// репозитории, и общий кэш показал бы дерево одного проекта в другом. Ошибка
// такого рода не бросается в глаза — файлы выглядят настоящими, просто не те.
const projects = new Map(); // projectId → { dirs, expanded, at }

/** Состояние одного проекта; заводится при первом обращении. */
function projectStore(project) {
  const key = project || '';
  let store = projects.get(key);
  if (!store) {
    store = { dirs: new Map(), expanded: new Set(['']), at: 0 };
    projects.set(key, store);
  }
  expireIfStale(store);
  return store;
}

function expireIfStale(store) {
  if (store.at && Date.now() - store.at > TTL_MS) {
    store.dirs.clear();
    store.at = 0;
  }
}

/** Снимок кэша листингов в виде объекта, каким его ждёт дерево: dirPath → nodes. */
export function readDirs(project) {
  return Object.fromEntries(projectStore(project).dirs);
}

/**
 * Листинг одного каталога (undefined — его в кэше нет). Отвечает на «нужно ли
 * запрашивать этот каталог» без снимка всего кэша: спрашивают из колбэков и
 * эффектов, где состояние компонента было бы лишним зеркалом.
 */
export function readDir(project, dir) {
  return projectStore(project).dirs.get(dir);
}

/** Раскрытые каталоги на момент прошлого визита в раздел. */
export function readExpanded(project) {
  return new Set(projectStore(project).expanded);
}

/** Кладёт в кэш листинги нескольких каталогов: { dirPath: nodes }. */
export function putDirs(project, entries) {
  const store = projectStore(project);
  for (const [dir, nodes] of Object.entries(entries)) {
    store.dirs.set(dir, nodes);
  }
  store.at = Date.now();
}

export function putExpanded(project, expanded) {
  projectStore(project).expanded = new Set(expanded);
}

/** Только для тестов: очистить кэш между кейсами (модуль живёт дольше рендера). */
export function resetFileTreeCache() {
  projects.clear();
}

/**
 * Выбрасывает закэшированные листинги всех проектов, но оставляет `expanded`.
 *
 * Для git-команды, которая сдвинула дерево целиком: листинги после неё
 * недостоверны, а вот какие каталоги пользователь раскрыл — от коммита не
 * зависит. Полная очистка сносила бы и это, а записать `expanded` обратно
 * некому: эффект, который его сохраняет, срабатывает только при изменении
 * самого набора, — и дерево схлопывалось бы после каждой команды.
 */
export function invalidateFileListings() {
  for (const store of projects.values()) {
    store.dirs.clear();
    store.at = 0;
  }
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
 * Сбрасывает в кэше ОДНОГО проекта листинги каталога-предка изменённого пути И
 * всех ЕГО предков — правка файлового инструмента (createFile/editFile) из чата может
 * как добавить файл в уже известный каталог, так и завести новый вложенный
 * каталог, которого раньше не было в листинге его родителя. Сам путь `path`
 * (не будучи каталогом) в кэше не лежит — трогать нечего.
 */
export function invalidatePath(project, path) {
  const { dirs } = projectStore(project);
  ancestorsOf(path).forEach((dir) => dirs.delete(dir));
}
