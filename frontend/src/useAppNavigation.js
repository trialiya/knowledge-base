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
 * URL-схема:
 *   ?view=chat|knowledge & chat=<id> & doc=<id> & tab=<docTab> & search=<q> & mode=<m>
 *
 * Состояние:
 *   {
 *     view:    'chat' | 'knowledge'
 *     chatId:  string | null      — последний открытый чат (живёт даже в KB-view)
 *     docId:   string | null      — документ, активный для ТЕКУЩЕЙ записи истории
 *     docTab:  'summary' | 'content' | ...
 *     search:  string             — поисковый запрос KB
 *     mode:    'hybrid' | ...      — режим поиска KB
 *   }
 *
 * Почему docId в состоянии берётся строго из URL (а «последний документ» хранится
 * отдельно в lastKbRef):
 *   docId в nav обязан соответствовать адресу — иначе экран рассинхронизируется
 *   с URL при «Назад»/«Вперёд». Но chat-URL по схеме НЕ содержит doc, поэтому
 *   любой «Назад» между чатами обнулил бы docId. Чтобы клик по вкладке «База
 *   знаний» всё равно открывал последний читанный документ, мы держим его в
 *   lastKbRef (вне URL) и подставляем в switchView, когда для текущей записи
 *   документ/поиск не активны.
 */

// ── URL <-> state ───────────────────────────────────────────────────────────

function readUrl() {
  const p = new URLSearchParams(window.location.search);
  const view = p.get('view');
  return {
    view: view === 'chat' || view === 'knowledge' ? view : null,
    chatId: p.get('chat') || null,
    docId: p.get('doc') || null,
    docTab: p.get('tab') || 'summary',
    search: p.get('search') || '',
    mode: p.get('mode') || 'hybrid',
  };
}

/**
 * Строит query-строку из состояния. В URL попадают ТОЛЬКО параметры,
 * релевантные текущему view:
 *   • view=chat       → view, chat               (doc/tab/search/mode — НЕ пишутся)
 *   • view=knowledge  → view, chat, doc | search → (взаимоисключающие)
 */
function buildSearch(nav) {
  const p = new URLSearchParams();
  p.set('view', nav.view);

  // chatId сохраняем в обоих view, чтобы возврат в чат помнил активный чат.
  if (nav.chatId) p.set('chat', nav.chatId);

  if (nav.view === 'knowledge') {
    if (nav.docId) {
      p.set('doc', nav.docId);
      if (nav.docTab && nav.docTab !== 'summary') p.set('tab', nav.docTab);
    } else if (nav.search) {
      p.set('search', nav.search);
      if (nav.mode) p.set('mode', nav.mode);
    }
  }
  return `?${p.toString()}`;
}

/** Начальное состояние: из URL, с разумными дефолтами. */
function initialNav() {
  const u = readUrl();
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
  // Нужна, потому что docId в nav обнуляется при «Назад» на chat-запись (в
  // chat-URL doc не пишется). Здесь же мы помним последний реально открытый
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
    const next = buildSearch(nav);
    const current = window.location.search || '?';
    if (next === current) return; // нет изменений — не плодим записи истории
    window.history.pushState({}, '', next);
  }, [nav]);

  // На старте гарантируем, что в истории есть запись с явным view (иначе
  // первый «Назад» вести некуда).
  useEffect(() => {
    const current = window.location.search || '?';
    const canonical = buildSearch(navRef.current);
    if (current !== canonical) {
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
        // chatId: в URL присутствует в обоих view (buildSearch его всегда пишет),
        // поэтому при возврате на chat-запись он там есть. Fallback на prev нужен
        // лишь как страховка для записей, сделанных до появления chat в URL.
        chatId: u.chatId ?? prev.chatId,
        // docId: СТРОГО из URL. buildSearch пишет doc только когда он реально
        // активен (view=knowledge и нет поиска). Если в URL его нет — значит для
        // этой записи истории документ не активен (идёт поиск, либо view=chat).
        // Подмешивать prev.docId здесь нельзя: при возврате на запись поиска это
        // вернуло бы устаревший документ, и экран рассинхронизировался бы с URL.
        // Память «последнего документа для вкладки Чат» живёт в lastKbRef и
        // восстанавливается в switchView, а не здесь.
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
   * Переключить верхнюю вкладку. Для KB восстанавливает последний документ,
   * если для текущей записи истории документ/поиск не активны (например, после
   * «Назад» между чатами docId в состоянии обнулён, но lastKbRef его помнит).
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

  return { nav, switchView, openDoc, setDocTab, setSearch, openChat };
}
