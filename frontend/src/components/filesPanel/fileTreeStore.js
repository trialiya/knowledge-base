// ─── Кэш файлового дерева, переживающий размонтирование панели ──────────────
// FilesPanel монтируется только пока открыт раздел «Файлы» (см. App.js), а
// вместе с ним умирало и состояние useFileTree — возврат в раздел заново
// выкачивал всю цепочку каталогов до открытого файла. Кэш вынесен в модуль:
// дерево при возврате рисуется мгновенно, а запрос уходит только за тем, чего
// в нём нет.
//
// Данные из git, а репозиторий живёт своей жизнью (коммиты, правки файловыми
// инструментами), поэтому кэш не вечен: если к нему не обращались дольше TTL,
// он целиком сбрасывается — внутри же одного «сеанса просмотра» листинги
// переиспользуются, как и раньше при жизни одного монтирования.

const TTL_MS = 60_000;

const store = {
  dirs: new Map(), // dirPath → GitFileNode[]
  expanded: new Set(['']),
  at: 0, // время последней записи листингов
};

function expireIfStale() {
  if (store.at && Date.now() - store.at > TTL_MS) {
    store.dirs.clear();
    store.expanded = new Set(['']);
    store.at = 0;
  }
}

/** Снимок кэша листингов в виде объекта, каким его ждёт дерево: dirPath → nodes. */
export function readDirs() {
  expireIfStale();
  return Object.fromEntries(store.dirs);
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
