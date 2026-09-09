import { SEARCH_MODE } from '@/constants/searchMode';
import { normalizeScope } from '@/constants/searchScope';
import { readPanelState } from './panelState';
import { decodeSegment, chatPath, docPath, filesPath, KNOWLEDGE_PATH, KB_SEARCH_PATH, SEARCH_PATH } from './urlScheme';

/**
 * ──────────────────────────────────────────────────────────────────────────
 * navUrl — кодек адреса: URL ⇄ объект `nav`.
 * ──────────────────────────────────────────────────────────────────────────
 *
 * navStore.js отвечает за историю и за то, КОГДА адрес меняется, а здесь —
 * только КАК он выглядит. Пишет в window.history один лишь стор; отсюда никто
 * в неё не ходит.
 *
 * ── URL-схема ───────────────────────────────────────────────────────────────
 * ПУТЬ — это «что открыто» (идентичность ресурса), QUERY — «как показано»
 * (состояние экрана):
 *
 *   /chat                                  чат, конкретный не выбран
 *   /chat/<chatId>                         конкретный чат ('new' — черновик)
 *   /chat/<chatId>?find=&msg=<id>          запрос find-бара и сообщение, с которого начать
 *   /knowledge                             база знаний, ничего не выбрано
 *   /knowledge/doc/<docId>                 документ или папка
 *   /knowledge/search?q=<q>&mode=<m>       результаты поиска по базе знаний
 *   /search?q=<q>&in=<категория>           единый поиск (файлы/документы/чаты)
 *   /files                                 корень репозитория
 *   /files/<path/to/file>                  файл или каталог (путь — в самом пути)
 *   /admin
 *   /settings
 *
 * Query-параметры (пишутся только когда отличаются от дефолта, чтобы адреса
 * оставались короткими и читаемыми):
 *
 *   ?q=, ?mode=     запрос и режим поиска по документам (дефолт режима — hybrid)
 *   ?in=<категория> единый поиск: файлы | документы | чаты (дефолта нет, см. buildUrl)
 *   ?path=, ?project=, ?regex=1, ?untracked=1  единый поиск: фильтры категории «файлы»
 *   ?find=<запрос>  что подсветить в открытом файле, документе или чате (дефолт — ничего)
 *   ?section=<путь> документ: раздел, к которому прокрутить, — путь заголовков
 *                   в форме бэкенда («Установка > Docker»); только вместе с find
 *   ?changes=1      файлы: слева список незакоммиченных изменений (дефолт — дерево)
 *   ?rev=<ревизия>  снимок коммита/ветки/тега (дефолт — рабочее дерево): и в
 *                   файлах, и как фильтр единого поиска
 *   ?left=0         левая панель свёрнута (дефолт — раскрыта)
 *   ?right=<tab>    правая панель раскрыта на вкладке (дефолт — свёрнута)
 *
 * ── Почему chat/doc/path больше НЕ висят в query всех разделов ──────────────
 * Адрес описывает ровно то, что открыто, а «последнее открытое» каждого раздела
 * помнит navStore (вне URL) и подставляет switchView: URL — состояние ЭТОЙ
 * записи истории, память стора — «куда вернуться».
 *
 * Строго из URL берутся и docId/chatId/filePath: подмешивать сюда память нельзя,
 * иначе «Назад» на запись без ресурса вернул бы устаревший экран, разъехавшийся
 * с адресом.
 *
 * ── Обратная совместимость ──────────────────────────────────────────────────
 * Старые ссылки (`/?doc=5`, `/knowledge?doc=5&tab=content`, `?view=settings&chat=…`,
 * `/files?path=…`) продолжают открываться: readUrl понимает и старую форму, а
 * канонизирующий replaceState на старте переписывает адрес в новую схему.
 * Вкладки центра `?tab=` больше нет — те её значения, что переехали в правую
 * панель, разбираются в `?right=`.
 */

const TOP_VIEWS = ['chat', 'knowledge', 'files', 'search', 'admin', 'settings'];

// ── URL <-> state ───────────────────────────────────────────────────────────

/** Текущий адрес целиком (путь + query) — для сравнения с целевым. */
export function currentUrl() {
  return window.location.pathname + window.location.search;
}

/**
 * Разобрать текущий адрес. Понимает и новую схему (ресурс в пути), и старую
 * (ресурс в query) — вторая нужна, чтобы ранее сохранённые ссылки открывались.
 */
export function readUrl() {
  const p = new URLSearchParams(window.location.search);
  const segs = window.location.pathname.split('/').filter(Boolean).map(decodeSegment);

  let view = TOP_VIEWS.includes(segs[0]) ? segs[0] : null;
  if (!view) {
    // Legacy: view жил в query (?view=settings).
    const legacy = p.get('view');
    if (TOP_VIEWS.includes(legacy)) view = legacy;
  }

  // Чат: /chat/<id> (legacy: ?chat=<id>).
  let chatId = null;
  let chatFind = '';
  let chatMsg = '';
  if (view === 'chat') {
    chatId = segs[1] || p.get('chat') || null;
    // Запрос find-бара чата — как и в файлах: состояние экрана, но в адресе,
    // иначе ссылка на найденное сообщение и F5 теряли бы подсветку. Сообщение,
    // с которого пришли из поиска, — как раздел у документа: без запроса
    // смысла не имеет, вести к нему некому.
    chatFind = p.get('find') || '';
    chatMsg = chatFind ? p.get('msg') || '' : '';
  }

  // База знаний: /knowledge/doc/<id> | /knowledge/search?q= (legacy: ?doc= | ?search=).
  let docId = null;
  let search = '';
  let docFind = '';
  let docSection = '';
  if (view === 'knowledge') {
    if (segs[1] === 'doc' && segs[2]) {
      docId = segs[2];
      // Запрос find-бара и раздел, с которых сюда пришли из поиска, — как у
      // файла: состояние экрана, но в адресе, иначе ссылка на найденное и F5
      // теряли бы место. Раздел без запроса смысла не имеет — к нему ведёт
      // именно активное совпадение.
      docFind = p.get('find') || '';
      docSection = docFind ? p.get('section') || '' : '';
    } else if (segs[1] === 'search') {
      search = p.get('q') || '';
    } else {
      docId = p.get('doc') || null;
      if (!docId) search = p.get('search') || '';
    }
  } else if (!view) {
    // Legacy без раздела в пути: `/?doc=N`. Это ИСТОРИЧЕСКАЯ форма doc-ссылки —
    // именно её хранят markdown документов и сообщения чата (DocumentLinkRewriter,
    // системный промпт), поэтому «открыть в новой вкладке» на такой ссылке обязано
    // показать документ, а не свалиться в чат по умолчанию. Раздел допишет
    // initialNav/popstate: docId/search — это всегда база знаний.
    docId = p.get('doc') || null;
    if (!docId) search = p.get('search') || '';
  }

  // Файлы: /files/<path…> (legacy: ?path=), проект — в query (см. urlScheme.filesUrl).
  let filePath = '';
  let fileProject = '';
  let fileChanges = false;
  let fileRev = '';
  let fileFind = '';
  let fileFindRegex = false;
  if (view === 'files') {
    filePath = segs.length > 1 ? segs.slice(1).join('/') : p.get('path') || '';
    fileProject = p.get('project') || '';
    // Режим левого блока — состояние экрана, а не ресурс: путь в адресе один и
    // тот же независимо от того, из дерева его открыли или из списка изменений.
    fileChanges = p.get('changes') === '1';
    // Ревизия — тоже состояние экрана: путь в адресе один и тот же, меняется
    // только снимок, в котором его читают. Пусто — рабочее дерево.
    fileRev = p.get('rev') || '';
    // Что подсвечивать в открытом файле: запрос, с которым сюда пришли из
    // поиска, или набранный в самом файле. Тоже состояние экрана — файл он не
    // меняет, — но именно в адресе: ссылкой на найденное делятся, и после F5
    // подсветка обязана остаться.
    fileFind = p.get('find') || '';
    fileFindRegex = p.get('re') === '1';
  }

  // Единый поиск: /search?q=…&in=… — запрос и категория, дальше фильтры этой
  // категории. Всё это состояние экрана, а не ресурс: открытого объекта у
  // раздела нет, есть запрос и то, как его показать.
  let searchQuery = '';
  let searchScope = '';
  let searchPath = '';
  let searchProject = '';
  let searchRev = '';
  let searchRegex = false;
  let searchUntracked = false;
  if (view === 'search') {
    searchQuery = p.get('q') || '';
    searchScope = normalizeScope(p.get('in'));
    searchPath = p.get('path') || '';
    searchProject = p.get('project') || '';
    searchRev = p.get('rev') || '';
    searchRegex = p.get('regex') === '1';
    searchUntracked = p.get('untracked') === '1';
  }

  // Legacy `?tab=`: раньше это была вкладка ЦЕНТРА. Те из них, что переехали в
  // правую панель, открываем в ней; `tab=content` терять не жалко — содержимое
  // теперь и так в центре.
  const legacyTab = p.get('tab');
  const legacyRightTab = legacyTab && legacyTab !== 'content' ? legacyTab : null;

  return {
    view,
    chatId,
    chatFind,
    chatMsg,
    docId,
    docFind,
    docSection,
    search,
    mode: p.get('mode') || SEARCH_MODE.HYBRID,
    filePath,
    fileProject,
    fileChanges,
    fileRev,
    fileFind,
    fileFindRegex,
    searchQuery,
    searchScope,
    searchPath,
    searchProject,
    searchRev,
    searchRegex,
    searchUntracked,
    leftCollapsed: p.get('left') === '0',
    rightTab: p.get('right') || legacyRightTab,
    // Есть ли в адресе явная раскладка панелей. Если нет — берём запомненную
    // для этого раздела (иначе ссылка без параметров всегда сбрасывала бы её).
    hasPanelParams: p.has('left') || p.has('right') || !!legacyRightTab,
    // Legacy ?chat= в адресе НЕ чат-раздела: в новую схему не попадает, но как
    // «последний чат» пригодится — иначе старая ссылка теряла бы его.
    legacyChatId: view !== 'chat' ? p.get('chat') || null : null,
  };
}

/** Построить адрес (путь + query) из состояния. */
export function buildUrl(nav) {
  const p = new URLSearchParams();
  let path;

  switch (nav.view) {
    case 'knowledge':
      if (nav.docId) {
        path = docPath(nav.docId);
        if (nav.docFind) p.set('find', nav.docFind);
        if (nav.docFind && nav.docSection) p.set('section', nav.docSection);
      } else if (nav.search) {
        path = KB_SEARCH_PATH;
        p.set('q', nav.search);
        if (nav.mode && nav.mode !== SEARCH_MODE.HYBRID) p.set('mode', nav.mode);
      } else {
        path = KNOWLEDGE_PATH;
      }
      break;
    case 'files':
      path = filesPath(nav.filePath);
      // Дефолтный проект в адрес не пишем — как и любое значение по умолчанию
      // в этой схеме; адрес без проекта означает именно его.
      if (nav.fileProject) p.set('project', nav.fileProject);
      if (nav.fileChanges) p.set('changes', '1');
      if (nav.fileRev) p.set('rev', nav.fileRev);
      if (nav.fileFind) p.set('find', nav.fileFind);
      // Флаг регулярки без самого запроса подсвечивать нечему.
      if (nav.fileFind && nav.fileFindRegex) p.set('re', '1');
      break;
    case 'search':
      path = SEARCH_PATH;
      if (nav.searchQuery) p.set('q', nav.searchQuery);
      // Категория пишется всегда: дефолта у неё нет — она зависит от раздела,
      // из которого запустили поиск, и адрес без неё означал бы «любую».
      p.set('in', nav.searchScope);
      if (nav.searchPath) p.set('path', nav.searchPath);
      // Как и в «Файлах»: дефолтный репозиторий в адрес не пишем.
      if (nav.searchProject) p.set('project', nav.searchProject);
      if (nav.searchRev) p.set('rev', nav.searchRev);
      if (nav.searchRegex) p.set('regex', '1');
      if (nav.searchUntracked) p.set('untracked', '1');
      if (nav.mode && nav.mode !== SEARCH_MODE.HYBRID) p.set('mode', nav.mode);
      break;
    case 'chat':
      path = chatPath(nav.chatId);
      if (nav.chatFind) p.set('find', nav.chatFind);
      if (nav.chatFind && nav.chatMsg) p.set('msg', nav.chatMsg);
      break;
    case 'admin':
      path = '/admin';
      break;
    case 'settings':
      path = '/settings';
      break;
    default:
      path = '/chat';
  }

  // Раскладка панелей — одинаково во всех разделах, только не-дефолтная.
  if (nav.leftCollapsed) p.set('left', '0');
  if (nav.rightTab) p.set('right', nav.rightTab);

  const qs = p.toString();
  return path + (qs ? `?${qs}` : '');
}

/** Состояние из разобранного адреса; раскладку панелей называет вызывающий. */
function toNav(u, view, panels) {
  return {
    view,
    chatId: u.chatId,
    chatFind: u.chatFind,
    chatMsg: u.chatMsg,
    docId: u.docId,
    docFind: u.docFind,
    docSection: u.docSection,
    search: u.search,
    mode: u.mode,
    filePath: u.filePath,
    fileProject: u.fileProject,
    fileChanges: u.fileChanges,
    fileRev: u.fileRev,
    fileFind: u.fileFind,
    fileFindRegex: u.fileFindRegex,
    searchQuery: u.searchQuery,
    searchScope: normalizeScope(u.searchScope),
    searchPath: u.searchPath,
    searchProject: u.searchProject,
    searchRev: u.searchRev,
    searchRegex: u.searchRegex,
    searchUntracked: u.searchUntracked,
    leftCollapsed: panels.leftCollapsed,
    rightTab: panels.rightTab,
  };
}

/**
 * Раздел из адреса. Без раздела в пути — по ресурсу: doc/search — это всегда
 * база знаний, иначе чат.
 */
function viewOf(u) {
  return u.view || (u.docId || u.search ? 'knowledge' : 'chat');
}

/**
 * Начальное состояние: из URL, с разумными дефолтами. Раскладка панелей —
 * явная из адреса, иначе запомненная для этого раздела: ссылка без параметров
 * не должна сбрасывать то, как раздел настроили.
 */
export function initialNav() {
  const u = readUrl();
  const view = viewOf(u);
  const panels = u.hasPanelParams ? { leftCollapsed: u.leftCollapsed, rightTab: u.rightTab } : readPanelState(view);
  return toNav(u, view, panels);
}

/**
 * Состояние записи истории, на которую вернулся браузер («Назад»/«Вперёд»).
 * Всё СТРОГО из адреса: раскладка панелей записана в него только когда
 * отличается от дефолта, поэтому её отсутствие — это именно дефолт для той
 * записи, а ресурс без подмешивания памяти — иначе возврат на запись без
 * ресурса показал бы устаревший экран, разъехавшийся с адресом.
 */
export function popNav() {
  const u = readUrl();
  return toNav(u, viewOf(u), { leftCollapsed: u.leftCollapsed, rightTab: u.rightTab });
}

/*
 * ──────────────────────────────────────────────────────────────────────────
 * SPA-fallback (обязательно для путь-роутинга)
 * ──────────────────────────────────────────────────────────────────────────
 * Dev (vite): dev-сервер отдаёт index.html на любой html-запрос, а на бэкенд
 *   (:8080) уходит только /api (см. server.proxy в vite.config.js) — так что
 *   вложенные пути при прямом заходе/перезагрузке работают «из коробки».
 *
 * Prod: сервер статики должен отдавать index.html на неизвестные пути, ВКЛЮЧАЯ
 *   вложенные (/chat/<id>, /knowledge/doc/<id>, /files/<path…>).
 *   • Spring Boot (если он же раздаёт build) — см. SpaForwardController:
 *
 *       @GetMapping({ "/chat/**", "/knowledge/**", "/files/**", "/admin", "/settings" })
 *       String forward() { return "forward:/index.html"; }
 *
 *   • nginx:  location / { try_files $uri /index.html; }
 */
