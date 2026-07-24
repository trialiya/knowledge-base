import { useState, useEffect, useRef, useCallback } from 'react';
import { DOC_TAB } from './constants/docTabs';
import { SEARCH_MODE } from './constants/searchMode';
import { readPanelState, savePanelState } from './panelState';

/**
 * ──────────────────────────────────────────────────────────────────────────
 * useAppNavigation — единственный владелец навигационного состояния.
 * ──────────────────────────────────────────────────────────────────────────
 *
 * Принципы:
 *   • ВЕСЬ URL-стейт живёт здесь, в одном объекте `nav`.
 *   • Только этот хук пишет в window.history и слушает popstate.
 *     Никакие другие компоненты историю не трогают.
 *   • Каждый осознанный ПЕРЕХОД = ровно один pushState.
 *     Смена раскладки панелей — replaceState (см. ниже).
 *   • popstate просто читает URL обратно в состояние — UI перерисовывается.
 *
 * ── URL-схема ───────────────────────────────────────────────────────────────
 * ПУТЬ — это «что открыто» (идентичность ресурса), QUERY — «как показано»
 * (состояние экрана):
 *
 *   /chat                                  чат, конкретный не выбран
 *   /chat/<chatId>                         конкретный чат ('new' — черновик)
 *   /knowledge                             база знаний, ничего не выбрано
 *   /knowledge/doc/<docId>                 документ или папка
 *   /knowledge/search?q=<q>&mode=<m>       результаты поиска
 *   /files                                 корень репозитория
 *   /files/<path/to/file>                  файл или каталог (путь — в самом пути)
 *   /admin
 *   /settings
 *
 * Query-параметры (пишутся только когда отличаются от дефолта, чтобы адреса
 * оставались короткими и читаемыми):
 *
 *   ?tab=<docTab>   вкладка центра у документа KB (дефолт summary)
 *   ?q=, ?mode=     запрос и режим поиска KB (дефолт режима — hybrid)
 *   ?left=0         левая панель свёрнута (дефолт — раскрыта)
 *   ?right=<tab>    правая панель раскрыта на вкладке (дефолт — свёрнута)
 *
 * ── Почему chat/doc/path больше НЕ висят в query всех разделов ──────────────
 * Раньше `?chat=<id>` дублировался в каждый адрес, чтобы возврат в чат помнил
 * активный чат. Теперь адрес описывает ровно то, что открыто, а «последнее
 * открытое» каждого раздела помнит memoryRef (вне URL) и подставляет switchView.
 * Тот же приём давно работал для документа KB, теперь он единый для всех
 * разделов: URL — состояние ЭТОЙ записи истории, memoryRef — «куда вернуться».
 *
 * Строго из URL берутся и docId/chatId/filePath: подмешивать сюда память нельзя,
 * иначе «Назад» на запись без ресурса вернул бы устаревший экран, разъехавшийся
 * с адресом.
 *
 * ── Панели и история ────────────────────────────────────────────────────────
 * Сворачивание панели — это НЕ переход: пять кликов по тумблеру не должны
 * требовать пяти нажатий «Назад». Поэтому раскладка пишется через replaceState
 * (адрес остаётся копируемым), а сама раскладка каждого раздела запоминается в
 * localStorage (panelState.js) и восстанавливается при возврате в раздел.
 *
 * ── Обратная совместимость ──────────────────────────────────────────────────
 * Старые ссылки (`/knowledge?doc=5&tab=content`, `?view=settings&chat=…`,
 * `/files?path=…`) продолжают открываться: readUrl понимает и старую форму, а
 * канонизирующий replaceState на старте переписывает адрес в новую схему.
 *
 * ⚠️ Деплой: путь-роутинг требует SPA-fallback на index.html, включая ВЛОЖЕННЫЕ
 * пути (/chat/<id>, /knowledge/doc/<id>, /files/<path…>). См. примечание в конце
 * файла и SpaForwardController на бэкенде.
 */

// Допустимые верхнеуровневые view (первый сегмент пути).
const TOP_VIEWS = ['chat', 'knowledge', 'files', 'admin', 'settings'];

// ── URL <-> state ───────────────────────────────────────────────────────────

/** Декодировать сегмент пути, не падая на битом percent-encoding. */
function decodeSegment(seg) {
  try {
    return decodeURIComponent(seg);
  } catch {
    return seg;
  }
}

/** Путь файла → сегменты URL ('a/b c.md' → 'a/b%20c.md'). */
function encodeFilePath(path) {
  return String(path || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/');
}

/** Текущий адрес целиком (путь + query) — для сравнения с целевым. */
function currentUrl() {
  return window.location.pathname + window.location.search;
}

/**
 * Разобрать текущий адрес. Понимает и новую схему (ресурс в пути), и старую
 * (ресурс в query) — вторая нужна, чтобы ранее сохранённые ссылки открывались.
 */
function readUrl() {
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
  if (view === 'chat') chatId = segs[1] || p.get('chat') || null;

  // База знаний: /knowledge/doc/<id> | /knowledge/search?q= (legacy: ?doc= | ?search=).
  let docId = null;
  let search = '';
  if (view === 'knowledge') {
    if (segs[1] === 'doc' && segs[2]) {
      docId = segs[2];
    } else if (segs[1] === 'search') {
      search = p.get('q') || '';
    } else {
      docId = p.get('doc') || null;
      if (!docId) search = p.get('search') || '';
    }
  }

  // Файлы: /files/<path…> (legacy: ?path=).
  let filePath = '';
  if (view === 'files') {
    filePath = segs.length > 1 ? segs.slice(1).join('/') : p.get('path') || '';
  }

  return {
    view,
    chatId,
    docId,
    docTab: p.get('tab') || DOC_TAB.SUMMARY,
    search,
    mode: p.get('mode') || SEARCH_MODE.HYBRID,
    filePath,
    leftCollapsed: p.get('left') === '0',
    rightTab: p.get('right') || null,
    // Есть ли в адресе явная раскладка панелей. Если нет — берём запомненную
    // для этого раздела (иначе ссылка без параметров всегда сбрасывала бы её).
    hasPanelParams: p.has('left') || p.has('right'),
    // Legacy ?chat= в адресе НЕ чат-раздела: в новую схему не попадает, но как
    // «последний чат» пригодится — иначе старая ссылка теряла бы его.
    legacyChatId: view !== 'chat' ? p.get('chat') || null : null,
  };
}

/** Построить адрес (путь + query) из состояния. */
function buildUrl(nav) {
  const p = new URLSearchParams();
  let path;

  switch (nav.view) {
    case 'knowledge':
      if (nav.docId) {
        path = `/knowledge/doc/${encodeURIComponent(nav.docId)}`;
        if (nav.docTab && nav.docTab !== DOC_TAB.SUMMARY) p.set('tab', nav.docTab);
      } else if (nav.search) {
        path = '/knowledge/search';
        p.set('q', nav.search);
        if (nav.mode && nav.mode !== SEARCH_MODE.HYBRID) p.set('mode', nav.mode);
      } else {
        path = '/knowledge';
      }
      break;
    case 'files': {
      const encoded = encodeFilePath(nav.filePath);
      path = encoded ? `/files/${encoded}` : '/files';
      break;
    }
    case 'chat':
      path = nav.chatId ? `/chat/${encodeURIComponent(nav.chatId)}` : '/chat';
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

/** Начальное состояние: из URL, с разумными дефолтами. */
function initialNav() {
  const u = readUrl();
  // view из пути приоритетен. Если его нет — инферим из наличия doc/search
  // (это всегда про базу знаний), иначе чат.
  const view = u.view || (u.docId || u.search ? 'knowledge' : 'chat');
  // Раскладка панелей: явная из адреса, иначе — запомненная для этого раздела.
  const panels = u.hasPanelParams ? { leftCollapsed: u.leftCollapsed, rightTab: u.rightTab } : readPanelState(view);

  return {
    view,
    chatId: u.chatId,
    docId: u.docId,
    docTab: u.docTab,
    search: u.search,
    mode: u.mode,
    filePath: u.filePath,
    leftCollapsed: panels.leftCollapsed,
    rightTab: panels.rightTab,
  };
}

// ── Hook ──────────────────────────────────────────────────────────────────

export default function useAppNavigation() {
  const [nav, setNav] = useState(initialNav);

  // Источник правды для записи URL — храним в ref, чтобы колбэки были стабильными
  // и не пересоздавались на каждый рендер.
  const navRef = useRef(nav);
  useEffect(() => {
    navRef.current = nav;
  }, [nav]);

  // ── Память «последнего открытого» в каждом разделе (вне URL) ────────────────
  // Адрес описывает только текущую запись истории, поэтому «Назад» на /chat
  // обнуляет docId, а /knowledge — chatId. Чтобы клик по вкладке возвращал туда
  // же, где пользователь был, помним последний ресурс каждого раздела здесь.
  const memoryRef = useRef({
    chatId: nav.chatId || readUrl().legacyChatId || null,
    docId: nav.docId || null,
    docTab: nav.docTab || DOC_TAB.SUMMARY,
    filePath: nav.filePath || '',
  });
  useEffect(() => {
    const m = memoryRef.current;
    if (nav.chatId) m.chatId = nav.chatId;
    if (nav.docId) {
      m.docId = nav.docId;
      m.docTab = nav.docTab || DOC_TAB.SUMMARY;
    }
    if (nav.view === 'files' && nav.filePath) m.filePath = nav.filePath;
  }, [nav.view, nav.chatId, nav.docId, nav.docTab, nav.filePath]);

  // Раскладку панелей запоминаем по разделам — при возврате в раздел она
  // восстановится (см. switchView).
  useEffect(() => {
    savePanelState(nav.view, { leftCollapsed: nav.leftCollapsed, rightTab: nav.rightTab });
  }, [nav.view, nav.leftCollapsed, nav.rightTab]);

  // Флаг: изменение пришло из popstate — значит URL уже актуален, писать НЕ нужно.
  const fromPopRef = useRef(false);
  // Способ записи следующего адреса. 'replace' ставят операции, которые не
  // являются переходом (сворачивание панелей): они не должны копить историю.
  const historyModeRef = useRef('push');

  // ── Запись URL при изменении состояния ────────────────────────────────────
  useEffect(() => {
    const mode = historyModeRef.current;
    historyModeRef.current = 'push';
    if (fromPopRef.current) {
      // Это состояние выставлено обработчиком popstate — URL уже совпадает.
      fromPopRef.current = false;
      return;
    }
    const next = buildUrl(nav);
    if (next === currentUrl()) return; // нет изменений — не плодим записи истории
    if (mode === 'replace') window.history.replaceState({}, '', next);
    else window.history.pushState({}, '', next);
  }, [nav]);

  // На старте гарантируем, что адрес канонический (ресурс переехал в путь, legacy
  // query срезан) — иначе первый «Назад» вести некуда.
  useEffect(() => {
    const canonical = buildUrl(navRef.current);
    if (canonical !== currentUrl()) {
      window.history.replaceState({}, '', canonical);
    }
  }, []);

  // ── popstate → состояние ───────────────────────────────────────────────────
  useEffect(() => {
    const onPop = () => {
      const u = readUrl();
      const view = u.view || (u.docId || u.search ? 'knowledge' : 'chat');
      // Раскладка панелей записана в адрес только когда отличается от дефолта,
      // поэтому её отсутствие — это именно дефолт для той записи истории.
      fromPopRef.current = true;
      setNav({
        view,
        // Ресурсы — СТРОГО из URL. Подмешивать память нельзя: при возврате на
        // запись без ресурса это вернуло бы устаревший экран, разъехавшийся с
        // адресом. «Куда вернуться» живёт в memoryRef и применяется в switchView.
        chatId: u.chatId,
        docId: u.docId,
        docTab: u.docTab,
        search: u.search,
        mode: u.mode,
        filePath: u.filePath,
        leftCollapsed: u.leftCollapsed,
        rightTab: u.rightTab,
      });
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  // ── Публичные методы навигации ─────────────────────────────────────────────

  /**
   * Переключить верхнеуровневый view (вкладка / страница).
   * Восстанавливает последний открытый ресурс раздела (чат / документ / путь) и
   * запомненную для него раскладку панелей.
   */
  const switchView = useCallback((view) => {
    setNav((prev) => {
      if (prev.view === view) return prev;
      const m = memoryRef.current;
      const panels = readPanelState(view);
      const next = { ...prev, ...panels, view };
      if (view === 'chat') next.chatId = prev.chatId || m.chatId || null;
      if (view === 'knowledge' && !prev.docId && !prev.search && m.docId) {
        next.docId = m.docId;
        next.docTab = m.docTab || DOC_TAB.SUMMARY;
      }
      if (view === 'files' && !prev.filePath) next.filePath = m.filePath || '';
      return next;
    });
  }, []);

  /** Открыть документ в KB (из чата, doc-ссылки, дерева). */
  const openDoc = useCallback((docId, docTab = DOC_TAB.SUMMARY) => {
    const id = docId == null ? null : String(docId);
    setNav((prev) => ({ ...prev, view: 'knowledge', docId: id, docTab, search: '', mode: prev.mode }));
  }, []);

  /** Сменить вкладку детали документа (summary/content/…). */
  const setDocTab = useCallback((docTab) => {
    setNav((prev) => (prev.docTab === docTab ? prev : { ...prev, docTab }));
  }, []);

  /** Запустить поиск в KB (сбрасывает выбранный документ). */
  const setSearch = useCallback((search, mode) => {
    setNav((prev) => ({
      ...prev,
      view: 'knowledge',
      docId: search ? null : prev.docId, // при активном поиске документ не выбран
      search,
      mode: mode ?? prev.mode,
    }));
  }, []);

  /** Открыть путь в файловом браузере ('' — корень репозитория). */
  const openFilePath = useCallback((path) => {
    setNav((prev) => ({ ...prev, view: 'files', filePath: path || '' }));
  }, []);

  /**
   * Открыть/сменить активный чат.
   *
   * `navigate: false` — «чат выбран фоном, а не пользователем»: так ChatWindow
   * сообщает про автовыбор при загрузке (первый чат из списка, либо пустой
   * черновик, когда чатов нет). Панель чата смонтирована всегда, в том числе
   * когда открыт другой раздел, поэтому безусловный переход уводил бы с
   * /files/<путь> или /knowledge/doc/<id> на /chat сразу после старта — ссылкой
   * на файл или документ нельзя было бы поделиться. Выбор при этом запоминается
   * (memoryRef) и попадает в адрес, как только пользователь вернётся в чат.
   */
  const openChat = useCallback((chatId, { navigate = true } = {}) => {
    const id = chatId == null ? null : String(chatId);
    if (id) memoryRef.current.chatId = id;
    setNav((prev) => (!navigate && prev.view !== 'chat' ? prev : { ...prev, view: 'chat', chatId: id }));
  }, []);

  // ── Раскладка панелей (replaceState: это не переход) ────────────────────────

  /** Свернуть/раскрыть левую панель текущего раздела. */
  const toggleLeftPanel = useCallback(() => {
    historyModeRef.current = 'replace';
    setNav((prev) => ({ ...prev, leftCollapsed: !prev.leftCollapsed }));
  }, []);

  /**
   * Раскрыть правую панель на вкладке `tab`, либо свернуть её (`null`).
   * Сеттер намеренно НЕ переключающий: панель раскрывают не только кликом по
   * тумблеру, но и действия (загрузили вложение → показать вложения), и для них
   * «повторный вызов сворачивает» дало бы ровно обратный эффект. Свернуть можно
   * кнопкой в шапке панели (она передаёт null).
   */
  const setRightTab = useCallback((tab) => {
    historyModeRef.current = 'replace';
    setNav((prev) => (prev.rightTab === (tab || null) ? prev : { ...prev, rightTab: tab || null }));
  }, []);

  return {
    nav,
    switchView,
    openDoc,
    setDocTab,
    setSearch,
    openChat,
    openFilePath,
    toggleLeftPanel,
    setRightTab,
  };
}

/*
 * ──────────────────────────────────────────────────────────────────────────
 * SPA-fallback (обязательно для путь-роутинга)
 * ──────────────────────────────────────────────────────────────────────────
 * Dev (react-scripts): webpack-dev-server отдаёт index.html на html-запросы, а
 *   `proxy` в package.json гонит на :8080 только нехтмл (API) — так что вложенные
 *   пути при прямом заходе/перезагрузке работают «из коробки».
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
