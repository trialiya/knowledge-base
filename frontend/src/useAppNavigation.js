import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * ──────────────────────────────────────────────────────────────────────────
 * useAppNavigation — единственный владелец навигационного состояния.
 * ──────────────────────────────────────────────────────────────────────────
 *
 * Принципы:
 *   • ВЕСЬ URL-стейт живёт здесь, в одном объекте `nav`.
 *   • Только этот хук пишет в window.history и слушает popstate.
 *     Никакие другие компоненты историю не трогают.
 *   • Каждый осознанный переход = ровно один pushState.
 *   • popstate просто читает URL обратно в состояние — UI перерисовывается.
 *
 * URL-схема (view — это ПУТЬ, остальное — query):
 *   /chat                                   → (+ ?chat=<id>)
 *   /knowledge?doc=<id>&tab=<docTab>        → документ
 *   /knowledge?search=<q>&mode=<m>          → поиск (взаимоисключающе с doc)
 *   /admin                                  → (+ ?chat=<id>)
 *   /settings                               → (+ ?chat=<id>)
 *
 *   • chat=<id> сохраняется во всех view, чтобы возврат в чат помнил активный.
 *   • doc/tab/search/mode пишутся ТОЛЬКО для /knowledge.
 *
 * Состояние:
 *   {
 *     view:    'chat' | 'knowledge' | 'admin' | 'settings'   ← путь
 *     chatId:  string | null      — последний открытый чат (живёт во всех view)
 *     docId:   string | null      — документ, активный для ТЕКУЩЕЙ записи истории
 *     docTab:  'summary' | 'content' | ...
 *     search:  string             — поисковый запрос KB
 *     mode:    'hybrid' | ...      — режим поиска KB
 *   }
 *
 * Почему docId в состоянии берётся строго из URL (а «последний документ» хранится
 * отдельно в lastKbRef):
 *   docId в nav обязан соответствовать адресу — иначе экран рассинхронизируется
 *   с URL при «Назад»/«Вперёд». Но chat/admin/settings-адреса по схеме НЕ содержат
 *   doc, поэтому любой «Назад» на такую запись обнулил бы docId. Чтобы клик по
 *   вкладке «База знаний» всё равно открывал последний читанный документ, мы
 *   держим его в lastKbRef (вне URL) и подставляем в switchView, когда для
 *   текущей записи документ/поиск не активны.
 *
 * admin / settings — полноценные верхнеуровневые view со своими путями
 * (/admin, /settings), поэтому браузерные «Назад/Вперёд» работают штатно, а
 * ссылку можно скопировать. doc/search в их адрес не попадают, но в состоянии
 * могут «пережить» переход — это не утекает в URL и помогает вернуть документ KB.
 *
 * ⚠️ Деплой: путь-роутинг требует, чтобы сервер отдавал index.html на /chat,
 * /knowledge, /admin, /settings (SPA-fallback). См. примечание в конце файла.
 * Базовый путь приложения предполагается «/» (CRA по умолчанию). Если фронт
 * раздаётся из подпапки — задайте homepage и учтите basename в *_PATH.
 */

// Допустимые верхнеуровневые view.
const TOP_VIEWS = ['chat', 'knowledge', 'admin', 'settings'];

// view ⇄ путь (первый сегмент URL).
const VIEW_TO_PATH = {
  chat: '/chat',
  knowledge: '/knowledge',
  admin: '/admin',
  settings: '/settings',
};
const PATH_TO_VIEW = {
  '/chat': 'chat',
  '/knowledge': 'knowledge',
  '/admin': 'admin',
  '/settings': 'settings',
};

// ── URL <-> state ───────────────────────────────────────────────────────────

/** view из первого сегмента пути ('/knowledge/foo' → knowledge), иначе null. */
function viewFromPathname(pathname) {
  const seg = '/' + (pathname.split('/')[1] || '');
  return PATH_TO_VIEW[seg] || null;
}

/** Текущий адрес целиком (путь + query) — для сравнения с целевым. */
function currentUrl() {
  return window.location.pathname + window.location.search;
}

function readUrl() {
  const p = new URLSearchParams(window.location.search);

  // view берём из ПУТИ. Legacy-фолбэк на старый ?view= — чтобы ранее сохранённые
  // ссылки (?view=settings&…) продолжали открываться; на старте они будут
  // канонизированы в путь через replaceState.
  let view = viewFromPathname(window.location.pathname);
  if (!view) {
    const legacy = p.get('view');
    if (TOP_VIEWS.includes(legacy)) view = legacy;
  }

  return {
    view,
    chatId: p.get('chat') || null,
    docId: p.get('doc') || null,
    docTab: p.get('tab') || 'summary',
    search: p.get('search') || '',
    mode: p.get('mode') || 'hybrid',
  };
}

/**
 * Строит адрес (путь + query) из состояния. В query попадают ТОЛЬКО параметры,
 * релевантные текущему view:
 *   • /chat                  → ?chat
 *   • /knowledge             → ?chat & (doc[&tab] | search[&mode])  (взаимоисключающе)
 *   • /admin | /settings     → ?chat
 */
function buildUrl(nav) {
  const p = new URLSearchParams();

  // chatId сохраняем во всех view, чтобы возврат в чат помнил активный чат.
  if (nav.chatId) p.set('chat', nav.chatId);

  // Только база знаний кладёт в URL документ/поиск.
  if (nav.view === 'knowledge') {
    if (nav.docId) {
      p.set('doc', nav.docId);
      if (nav.docTab && nav.docTab !== 'summary') p.set('tab', nav.docTab);
    } else if (nav.search) {
      p.set('search', nav.search);
      if (nav.mode) p.set('mode', nav.mode);
    }
  }

  const qs = p.toString();
  return (VIEW_TO_PATH[nav.view] || '/chat') + (qs ? `?${qs}` : '');
}

/** Начальное состояние: из URL, с разумными дефолтами. */
function initialNav() {
  const u = readUrl();
  // view из пути приоритетен (включая admin/settings). Если его нет — инферим из
  // наличия doc/search (это всегда про базу знаний), иначе чат.
  const view = u.view || (u.docId || u.search ? 'knowledge' : 'chat');
  return {
    view,
    chatId: u.chatId,
    docId: u.docId,
    docTab: u.docTab,
    search: u.search,
    mode: u.mode,
  };
}

// ── Hook ──────────────────────────────────────────────────────────────────

export default function useAppNavigation() {
  const [nav, setNav] = useState(initialNav);

  // Источник правды для записи URL — храним в ref, чтобы writeUrl был стабильным
  // и не пересоздавал колбэки на каждый рендер.
  const navRef = useRef(nav);
  useEffect(() => {
    navRef.current = nav;
  }, [nav]);

  // ── Память «последнего документа KB» (вне URL) ──────────────────────────────
  // Нужна, потому что docId в nav обнуляется при «Назад» на запись без doc в URL
  // (chat / admin / settings). Здесь же мы помним последний реально открытый
  // документ и его вкладку, чтобы switchView('knowledge') мог его восстановить.
  const lastKbRef = useRef({
    docId: nav.docId || null,
    docTab: nav.docTab || 'summary',
  });
  useEffect(() => {
    if (nav.docId) {
      lastKbRef.current = { docId: nav.docId, docTab: nav.docTab || 'summary' };
    }
  }, [nav.docId, nav.docTab]);

  // Флаг: изменение пришло из popstate — значит URL уже актуален, писать НЕ нужно.
  const fromPopRef = useRef(false);

  // ── Запись URL при изменении состояния ────────────────────────────────────
  useEffect(() => {
    if (fromPopRef.current) {
      // Это состояние выставлено обработчиком popstate — URL уже совпадает.
      fromPopRef.current = false;
      return;
    }
    const next = buildUrl(nav);
    if (next === currentUrl()) return; // нет изменений — не плодим записи истории
    window.history.pushState({}, '', next);
  }, [nav]);

  // На старте гарантируем, что адрес канонический (путь соответствует view, лишние
  // параметры срезаны, legacy ?view= переписан в путь) — иначе первый «Назад»
  // вести некуда.
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
      const prev = navRef.current;
      const view = u.view || (u.docId || u.search ? 'knowledge' : 'chat');
      fromPopRef.current = true;
      setNav({
        view,
        // chatId: в URL присутствует во всех view (buildUrl его всегда пишет),
        // поэтому при возврате на запись он там есть. Fallback на prev нужен лишь
        // как страховка для записей, сделанных до появления chat в URL.
        chatId: u.chatId ?? prev.chatId,
        // docId: СТРОГО из URL. buildUrl пишет doc только когда он реально активен
        // (view=knowledge и нет поиска). Если в URL его нет — значит для этой
        // записи истории документ не активен (поиск, чат, admin или settings).
        // Подмешивать prev.docId здесь нельзя: при возврате на запись без doc это
        // вернуло бы устаревший документ, и экран рассинхронизировался бы с URL.
        // Память «последнего документа» живёт в lastKbRef и восстанавливается в
        // switchView, а не здесь.
        docId: u.docId,
        docTab: u.docTab,
        search: u.search,
        mode: u.mode,
      });
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  // ── Публичные методы навигации ─────────────────────────────────────────────

  /**
   * Переключить верхнеуровневый view (вкладка / страница).
   * Подходит для chat | knowledge | admin | settings.
   * Для KB восстанавливает последний документ, если для текущей записи истории
   * документ/поиск не активны (например, после «Назад» на chat/admin/settings
   * docId в состоянии обнулён, но lastKbRef его помнит).
   */
  const switchView = useCallback((view) => {
    setNav((prev) => {
      if (prev.view === view) return prev;
      if (view === 'knowledge' && !prev.docId && !prev.search) {
        const last = lastKbRef.current;
        if (last?.docId) {
          return { ...prev, view, docId: last.docId, docTab: last.docTab || 'summary' };
        }
      }
      return { ...prev, view };
    });
  }, []);

  /** Открыть документ в KB (из чата, doc-ссылки, дерева). */
  const openDoc = useCallback((docId, docTab = 'summary') => {
    const id = docId == null ? null : String(docId);
    if (id) lastKbRef.current = { docId: id, docTab };
    setNav((prev) => ({ ...prev, view: 'knowledge', docId: id, docTab, search: '', mode: prev.mode }));
  }, []);

  /** Сменить вкладку детали документа (summary/content/…). */
  const setDocTab = useCallback((docTab) => {
    setNav((prev) => ({ ...prev, docTab }));
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

  /** Открыть/сменить активный чат. */
  const openChat = useCallback((chatId) => {
    const id = chatId == null ? null : String(chatId);
    setNav((prev) => ({ ...prev, view: 'chat', chatId: id }));
  }, []);

  /** Открыть админ-панель (свой адрес /admin). */
  const openAdmin = useCallback(() => {
    setNav((prev) => (prev.view === 'admin' ? prev : { ...prev, view: 'admin' }));
  }, []);

  /** Открыть настройки (свой адрес /settings). */
  const openSettings = useCallback(() => {
    setNav((prev) => (prev.view === 'settings' ? prev : { ...prev, view: 'settings' }));
  }, []);

  return { nav, switchView, openDoc, setDocTab, setSearch, openChat, openAdmin, openSettings };
}

/*
 * ──────────────────────────────────────────────────────────────────────────
 * SPA-fallback (обязательно для путь-роутинга)
 * ──────────────────────────────────────────────────────────────────────────
 * Dev (react-scripts): webpack-dev-server отдаёт index.html на html-запросы, а
 *   `proxy` в package.json гонит на :8080 только нехтмл (API) — так что /admin,
 *   /settings и пр. при прямом заходе/перезагрузке работают «из коробки».
 *
 * Prod: сервер статики должен отдавать index.html на неизвестные пути.
 *   • Spring Boot (если он же раздаёт build): добавьте форвард на index.html
 *     для известных маршрутов (REST под /api/** не затрагивается):
 *
 *       @Controller
 *       class SpaForwardController {
 *           @GetMapping({ "/chat", "/knowledge", "/admin", "/settings" })
 *           String forward() { return "forward:/index.html"; }
 *       }
 *
 *   • nginx:  location / { try_files $uri /index.html; }
 */
