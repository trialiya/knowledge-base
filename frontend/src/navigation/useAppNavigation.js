import { useState, useEffect, useRef, useCallback } from 'react';
import { normalizeScope } from '@/constants/searchScope';
import { readPanelState, savePanelState } from './panelState';
import { readUrl, buildUrl, currentUrl, initialNav } from './navUrl';

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
 * Как адрес устроен и как разбирается — в navUrl.js; здесь только то, когда он
 * меняется и какой записью истории.
 *
 * ── Панели и история ────────────────────────────────────────────────────────
 * Сворачивание панели — это НЕ переход: пять кликов по тумблеру не должны
 * требовать пяти нажатий «Назад». Поэтому раскладка пишется через replaceState
 * (адрес остаётся копируемым), а сама раскладка каждого раздела запоминается в
 * localStorage (panelState.js) и восстанавливается при возврате в раздел.
 *
 * ⚠️ Деплой: путь-роутинг требует SPA-fallback на index.html, включая ВЛОЖЕННЫЕ
 * пути (/chat/<id>, /knowledge/doc/<id>, /files/<path…>). См. примечание в конце
 * navUrl.js и SpaForwardController на бэкенде.
 */

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
  // Ленивая инициализация: аргумент useRef вычисляется на КАЖДОМ рендере, а
  // разбор адреса нужен ровно один раз (nav меняется часто — в т.ч. на каждый
  // тумблер панели).
  const memoryRef = useRef(null);
  if (memoryRef.current === null) {
    memoryRef.current = {
      chatId: nav.chatId || readUrl().legacyChatId || null,
      docId: nav.docId || null,
      filePath: nav.filePath || '',
      fileProject: nav.fileProject || '',
    };
  }
  useEffect(() => {
    const m = memoryRef.current;
    if (nav.chatId) m.chatId = nav.chatId;
    if (nav.docId) m.docId = nav.docId;
    if (nav.view === 'files' && nav.filePath) {
      m.filePath = nav.filePath;
      m.fileProject = nav.fileProject || '';
    }
  }, [nav.view, nav.chatId, nav.docId, nav.filePath, nav.fileProject]);

  // Раскладку панелей запоминаем по разделам — при возврате в раздел она
  // восстановится (см. switchView).
  useEffect(() => {
    savePanelState(nav.view, { leftCollapsed: nav.leftCollapsed, rightTab: nav.rightTab });
  }, [nav.view, nav.leftCollapsed, nav.rightTab]);

  // Флаг: изменение пришло из popstate — значит URL уже актуален, писать НЕ нужно.
  const fromPopRef = useRef(false);
  // Способ записи следующего адреса. Ставится ТЕМ, кто инициирует изменение
  // (pushNav / replaceNav), а не эффектом: setNav может вернуть прежнее
  // состояние (например, раскрытие уже раскрытой вкладки), тогда эффект не
  // запустится — и режим, сброшенный только в нём, протёк бы в следующий,
  // настоящий переход, съев запись истории.
  const historyModeRef = useRef('push');
  // Первую запись адреса всегда делаем через replaceState: на старте мы лишь
  // канонизируем то, что уже открыто (legacy-ссылка → новая схема), а не
  // переходим куда-то. Иначе «Назад» возвращал бы на исходный legacy-адрес,
  // который тут же канонизируется снова — кнопка выглядела бы сломанной.
  const wroteUrlRef = useRef(false);

  /** Переход: новая запись в истории. */
  const pushNav = useCallback((updater) => {
    historyModeRef.current = 'push';
    setNav(updater);
  }, []);

  /** Не переход (раскладка панелей): адрес обновляется на месте. */
  const replaceNav = useCallback((updater) => {
    historyModeRef.current = 'replace';
    setNav(updater);
  }, []);

  // ── Запись URL при изменении состояния ────────────────────────────────────
  useEffect(() => {
    if (fromPopRef.current) {
      // Это состояние выставлено обработчиком popstate — URL уже совпадает.
      fromPopRef.current = false;
      wroteUrlRef.current = true;
      return;
    }
    const mode = wroteUrlRef.current ? historyModeRef.current : 'replace';
    const next = buildUrl(nav);
    wroteUrlRef.current = true;
    if (next === currentUrl()) return; // нет изменений — не плодим записи истории
    if (mode === 'replace') window.history.replaceState({}, '', next);
    else window.history.pushState({}, '', next);
  }, [nav]);

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
  const switchView = useCallback(
    (view) => {
      pushNav((prev) => {
        if (prev.view === view) return prev;
        const m = memoryRef.current;
        const panels = readPanelState(view);
        const next = { ...prev, ...panels, view };
        if (view === 'chat') next.chatId = prev.chatId || m.chatId || null;
        if (view === 'knowledge' && !prev.docId && !prev.search && m.docId) next.docId = m.docId;
        if (view === 'files' && !prev.filePath) {
          next.filePath = m.filePath || '';
          next.fileProject = m.fileProject || '';
        }
        return next;
      });
    },
    [pushNav],
  );

  /** Открыть документ в KB (из чата, doc-ссылки, дерева). */
  const openDoc = useCallback(
    (docId) => {
      const id = docId == null ? null : String(docId);
      pushNav((prev) => ({ ...prev, view: 'knowledge', docId: id, search: '', mode: prev.mode }));
    },
    [pushNav],
  );

  /** Запустить поиск в KB (сбрасывает выбранный документ). */
  const setSearch = useCallback(
    (search, mode) => {
      pushNav((prev) => ({
        ...prev,
        view: 'knowledge',
        docId: search ? null : prev.docId, // при активном поиске документ не выбран
        search,
        mode: mode ?? prev.mode,
      }));
    },
    [pushNav],
  );

  /**
   * Запустить единый поиск (`/search`).
   *
   * Это ПЕРЕХОД: раздел меняется, и «Назад» обязано вернуть туда, откуда искали.
   * Категорию называет вызывающий — по умолчанию она равна разделу, из которого
   * запустили поиск (см. scopeForView), а не последней выбранной: человек ищет
   * то, на что смотрит.
   */
  const openSearch = useCallback(
    (query, scope) => {
      pushNav((prev) => ({
        ...prev,
        view: 'search',
        searchQuery: query || '',
        searchScope: normalizeScope(scope || prev.searchScope),
      }));
    },
    [pushNav],
  );

  /**
   * Сменить категорию или её фильтры, не трогая запрос.
   *
   * Через replaceNav: это уточнение одного и того же поиска — новый ресурс не
   * открывается, — и подбор фильтров не должен стоить пользователю по нажатию
   * «Назад» за каждую снятую галочку. Сам поиск как переход уже записан
   * openSearch.
   */
  const refineSearch = useCallback(
    (patch) => {
      replaceNav((prev) => {
        const next = { ...prev, ...patch };
        if (patch.searchScope) next.searchScope = normalizeScope(patch.searchScope);
        // Поле покинули, не тронув, — состояние не двигаем: смонтированы сразу
        // несколько разделов, и новый объект `nav` перерисовал бы их все.
        return Object.keys(patch).some((key) => next[key] !== prev[key]) ? next : prev;
      });
    },
    [replaceNav],
  );

  /**
   * Открыть путь в файловом браузере ('' — корень репозитория).
   *
   * @param project репозиторий пути; не передан — остаёмся в том, что открыт
   *   (клик по дереву не должен уводить в другой проект), а переход по ссылке из
   *   чата проект называет и панель переключает
   * @param options `{ changes, rev, find, findRegex }` — `changes`: каким показать
   *   левый блок (ссылка из вкладки «Репозиторий» ведёт к незакоммиченному,
   *   ссылка на файл — в дерево; не передан — режим остаётся тем, что был);
   *   `find`: что подсветить в открытом файле — его приносит переход из поиска
   */
  const openFilePath = useCallback(
    (path, project, options) => {
      pushNav((prev) => {
        const nextProject = project === undefined ? prev.fileProject : project || '';
        return {
          ...prev,
          view: 'files',
          filePath: path || '',
          fileProject: nextProject,
          // Режим левого блока — часть этого же перехода, а не отдельная запись:
          // отдельным setFileChanges (он через replaceNav) переход превратился бы
          // в замену, и «Назад» не вернуло бы туда, откуда ссылку нажали.
          fileChanges: options?.changes === undefined ? prev.fileChanges : !!options.changes,
          fileRev: nextFileRev(prev, nextProject, options),
          // Подсветка принадлежит переходу, а не файлу: открыли файл откуда-то
          // ещё — искать в нём нечего, и прежний запрос красил бы случайное.
          fileFind: options?.find || '',
          fileFindRegex: !!options?.find && !!options?.findRegex,
        };
      });
    },
    [pushNav],
  );

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
   *
   * Если открыт именно чат (просто /chat без id) — фоновый выбор всё же должен
   * попасть в адрес, иначе открытый чат нельзя скопировать ссылкой. Но это не
   * ПЕРЕХОД пользователя, поэтому пишем через replaceNav: иначе автовыбор при
   * каждой свежей загрузке /chat плодил бы лишнюю запись истории (/chat →
   * /chat/<id>), которую «Назад» не отличить от настоящего перехода — экран
   * при возврате на /chat визуально не меняется (ChatWindow держит свой выбор
   * в локальном стейте), и кнопка выглядит нерабочей.
   */
  const openChat = useCallback(
    (chatId, { navigate = true, find } = {}) => {
      const id = chatId == null ? null : String(chatId);
      if (id) memoryRef.current.chatId = id;
      // Запрос относится к тому чату, из-за которого сюда пришли: открывая
      // другой, его не тащим — подсвечивать в нём нечего.
      const chatFind = find || '';
      if (!navigate) {
        replaceNav((prev) => (prev.view !== 'chat' ? prev : { ...prev, chatId: id, chatFind }));
        return;
      }
      pushNav((prev) => ({ ...prev, view: 'chat', chatId: id, chatFind }));
    },
    [pushNav, replaceNav],
  );

  // ── Раскладка панелей (replaceState: это не переход) ────────────────────────

  /** Свернуть/раскрыть левую панель текущего раздела. */
  const toggleLeftPanel = useCallback(() => {
    replaceNav((prev) => ({ ...prev, leftCollapsed: !prev.leftCollapsed }));
  }, [replaceNav]);

  /**
   * Раскрыть правую панель на вкладке `tab`, либо свернуть её (`null`).
   * Сеттер намеренно НЕ переключающий: панель раскрывают не только кликом по
   * тумблеру, но и действия (загрузили вложение → показать вложения), и для них
   * «повторный вызов сворачивает» дало бы ровно обратный эффект. Свернуть можно
   * кнопкой в шапке панели (она передаёт null).
   */
  const setRightTab = useCallback(
    (tab) => {
      replaceNav((prev) => (prev.rightTab === (tab || null) ? prev : { ...prev, rightTab: tab || null }));
    },
    [replaceNav],
  );

  /**
   * Режим левого блока файлового браузера: дерево репозитория или список
   * незакоммиченных изменений. Через replaceNav по той же причине, что и
   * панели: это не переход к другому ресурсу — открытый путь остаётся тем же, —
   * и переключение туда-обратно не должно требовать двух «Назад».
   */
  const setFileChanges = useCallback(
    (changes) => {
      replaceNav((prev) => (prev.fileChanges === !!changes ? prev : { ...prev, fileChanges: !!changes }));
    },
    [replaceNav],
  );

  /**
   * Какую ревизию показывает файловый браузер: '' — рабочее дерево, иначе имя
   * ветки, тега или хеш. Через replaceNav по той же причине, что и режим
   * левого блока: путь остаётся тем же, меняется только снимок, и возврат в
   * рабочее дерево не должен стоить двух «Назад».
   */
  const setFileRev = useCallback(
    (rev) => {
      replaceNav((prev) => (prev.fileRev === (rev || '') ? prev : { ...prev, fileRev: rev || '' }));
    },
    [replaceNav],
  );

  /**
   * Что подсвечено в открытом файле ('' — ничего, бар закрыт). Через replaceNav
   * по той же причине, что режим левого блока и ревизия: файл остаётся тем же,
   * и набранный в баре запрос не должен стоить «Назад» на каждую букву.
   */
  const setFileFind = useCallback(
    (find, regex) => {
      const next = find || '';
      replaceNav((prev) =>
        prev.fileFind === next && prev.fileFindRegex === !!regex
          ? prev
          : { ...prev, fileFind: next, fileFindRegex: !!next && !!regex },
      );
    },
    [replaceNav],
  );

  /** Что подсвечено в открытом чате — по тем же правилам, что и в файле. */
  const setChatFind = useCallback(
    (find) => {
      const next = find || '';
      replaceNav((prev) => (prev.chatFind === next ? prev : { ...prev, chatFind: next }));
    },
    [replaceNav],
  );

  return {
    nav,
    switchView,
    openDoc,
    setSearch,
    openSearch,
    refineSearch,
    openChat,
    openFilePath,
    setFileChanges,
    setFileRev,
    setFileFind,
    setChatFind,
    toggleLeftPanel,
    setRightTab,
  };
}

/**
 * Ревизия следующего перехода в «Файлах».
 *
 * По умолчанию она переезжает вместе с путём: ссылка на файл, нажатая в снимке
 * коммита, открывает его в том же снимке. Уводит из снимка явный выбор ревизии
 * (`setFileRev`, он же `options.rev`) — и два перехода, в которых прежняя
 * ревизия означала бы не то, о чём просили:
 *
 * - смена репозитория: имя ветки или тега принадлежит своему репозиторию, в
 *   другом его либо нет (400 вместо дерева), либо оно называет чужой коммит.
 *   Сравнение идёт по тому, что стоит в адресе, а дефолтный проект в него не
 *   пишется — поэтому явно названный дефолтный (`project=kb` при пустом
 *   значении в адресе) читается как смена и ревизию сбрасывает. Это уход в
 *   рабочее дерево, а не чужой снимок, — ошибка в безопасную сторону;
 * - переход в режим изменений: незакоммиченные правки есть только у рабочего
 *   дерева, и панель, оставшись в снимке, молча показала бы вместо них файл на
 *   старой ревизии.
 */
function nextFileRev(prev, nextProject, options) {
  if (options?.rev !== undefined) return options.rev || '';
  if (options?.changes) return '';
  return nextProject === prev.fileProject ? prev.fileRev : '';
}
