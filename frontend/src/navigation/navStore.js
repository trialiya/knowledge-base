import { normalizeScope } from '@/constants/searchScope';
import { FILE_MODE } from '@/constants/fileModes';
import { readPanelState, savePanelState } from './panelState';
import { readUrl, buildUrl, currentUrl, initialNav, popNav } from './navUrl';

/**
 * ──────────────────────────────────────────────────────────────────────────
 * navStore — состояние навигации и единственный писатель window.history.
 * ──────────────────────────────────────────────────────────────────────────
 *
 * Обычный модуль без React: хук useAppNavigation лишь подписывает на него
 * рендер и вешает popstate. Всё, что решает, КОГДА и КАК меняется адрес,
 * живёт здесь и проверяется без рендера.
 *
 * Принципы:
 *   • ВЕСЬ URL-стейт — один объект `nav`; как он кодируется — в navUrl.js.
 *   • Одна точка записи — navigate(): считает следующее состояние из текущего
 *     и СИНХРОННО пишет адрес. Способ записи — аргумент этой записи, а не
 *     что-то, что читают позже: между вызовом и записью нет кадра, в который
 *     мог бы вклиниться чужой вызов и подменить его.
 *   • Один вызов перехода = ровно одна запись истории. Два перехода подряд
 *     из одного обработчика — две записи; переход, который не сводится к
 *     смене раздела (файл из поиска, сообщение чата), делает один метод —
 *     openFilePath, openChat, — а не пара «переключить раздел, потом открыть».
 *   • Холостой вызов (состояние не изменилось) не уведомляет подписчиков:
 *     смонтированы сразу несколько разделов, и новый объект `nav`
 *     перерисовал бы их все.
 *   • popstate читает адрес обратно в состояние — UI перерисовывается.
 *
 * ── Что не переход ─────────────────────────────────────────────────────────
 * Раскладка панелей, режим и ревизия «Файлов», запрос find-бара — состояние
 * экрана: пять кликов по тумблеру не должны требовать пяти нажатий «Назад».
 * Они пишутся через replaceState (адрес остаётся копируемым); раскладка
 * каждого раздела к тому же запоминается (panelState.js) и восстанавливается
 * при возврате в раздел. Запрос бара фиксируют по Enter и уходу фокуса, а не
 * на каждую букву: history.replaceState на каждую браузеры считают
 * злоупотреблением (Safari — с ошибкой).
 *
 * ── Уход с несохранёнными правками ─────────────────────────────────────────
 * `canLeave(prev, next)` спрашивают перед каждым переходом в другой раздел.
 * Отказ откладывает переход ЦЕЛИКОМ — как значение, до confirmLeave: раздел
 * не меняется, память не трогается, и то, что должно было открыться вместе с
 * ним, ждёт ответа вместе с ним. Спросить и уйти всё равно поэтому нельзя.
 * Ушли из раздела другим путём (в поиск, «Назад») — вопрос снят.
 *
 * «Назад»/«Вперёд» браузер отменить не даёт, а вопрос нужен и им: сменив
 * документ базы знаний, они выбросили бы несохранённые правки (уход в другой
 * раздел правки не теряет — база смонтирована всегда). Поэтому каждая запись
 * истории, которую пишет стор, помечена номером (`kbIndex`) в пределах сессии
 * вкладки (`kbSession`). Такой переход стор возвращает на место —
 * `history.go` на ту же разницу обратно — и откладывает до ответа;
 * «уйти» повторяет его тем же `history.go`. Запись без метки (чужая или из
 * прошлой загрузки страницы) пропускается без вопроса: разница до неё
 * неизвестна.
 *
 * ── Переход в другой раздел приносит его раскладку ─────────────────────────
 * Любой переход, меняющий раздел (вкладка, файл из чата, документ из поиска),
 * подставляет запомненную для целевого раздела раскладку панелей — иначе
 * раскладка раздела-источника утекла бы в адрес и в память нового.
 *
 * ⚠️ Деплой: путь-роутинг требует SPA-fallback на index.html, включая ВЛОЖЕННЫЕ
 * пути (/chat/<id>, /knowledge/doc/<id>, /files/<path…>). См. примечание в конце
 * navUrl.js и SpaForwardController на бэкенде.
 */

/**
 * @param {object}   [options]
 * @param {Function} [options.canLeave] `(prev, next) => boolean` — можно ли
 *   уйти из раздела `prev.view` в `next.view`; не задан — всегда можно
 * @param {Function} [options.canReplaceDoc] `() => boolean` — можно ли без
 *   вопроса сменить документ, открытый в базе знаний («Назад»/«Вперёд»);
 *   не задан — всегда можно
 */
export function createNavStore({ canLeave = () => true, canReplaceDoc = () => true } = {}) {
  let nav = initialNav();

  // ── Место в истории (см. «Уход с несохранёнными правками») ─────────────────
  // Сессия переживает перезагрузку: её метка лежит в самой записи.
  const loaded = window.history.state;
  const session = loaded?.kbSession || Math.random().toString(36).slice(2);
  let index = loaded?.kbSession === session && Number.isInteger(loaded.kbIndex) ? loaded.kbIndex : 0;
  const entry = () => ({ kbSession: session, kbIndex: index });
  // Следующий popstate вызван самим стором: 'restore' — возврат на место
  // отложенного перехода, 'replay' — его повтор после «уйти».
  let transit = null;

  // ── Память «последнего открытого» в каждом разделе (вне URL) ────────────────
  // Адрес описывает только текущую запись истории, поэтому «Назад» на /chat
  // обнуляет docId, а /knowledge — chatId. Чтобы клик по вкладке возвращал туда
  // же, где пользователь был, помним последний ресурс каждого раздела здесь.
  // Legacy ?chat= в адресе НЕ чат-раздела читается до канонизации — потом его
  // в адресе уже нет.
  const memory = {
    chatId: nav.chatId || readUrl().legacyChatId || null,
    docId: nav.docId || null,
    filePath: nav.filePath || '',
    fileProject: nav.fileProject || '',
  };

  // Отложенный переход: { updater, history, view } — ждёт ответа на вопрос
  // canLeave; { delta, view } — «Назад»/«Вперёд», ждущий canReplaceDoc; view —
  // раздел, в который просятся; null — вопроса нет.
  let pending = null;

  const listeners = new Set();
  // Снимок для useSyncExternalStore: новый объект только когда есть что
  // перерисовать.
  let snapshot = { nav, pendingView: null, pendingDiscard: false };

  function emit() {
    snapshot = { nav, pendingView: pending ? pending.view : null, pendingDiscard: pending?.delta != null };
    listeners.forEach((cb) => cb());
  }

  /**
   * Сменит ли состояние `next` документ, открытый в базе знаний. Её документ —
   * последний открытый в ней (`memory.docId`): в другом разделе он остаётся
   * смонтированным, а голый /knowledge его не сбрасывает.
   */
  const replacesDoc = (next) =>
    next.view === 'knowledge' && (!!next.search || (next.docId != null && next.docId !== memory.docId));

  /** Запомнить открытое и раскладку панелей раздела. */
  function remember(prev, next) {
    if (next.chatId) memory.chatId = next.chatId;
    if (next.docId) memory.docId = next.docId;
    if (next.view === 'files' && next.filePath) {
      memory.filePath = next.filePath;
      memory.fileProject = next.fileProject || '';
    }
    if (prev.view !== next.view || prev.leftCollapsed !== next.leftCollapsed || prev.rightTab !== next.rightTab) {
      savePanelState(next.view, { leftCollapsed: next.leftCollapsed, rightTab: next.rightTab });
    }
  }

  /** Записать состояние и адрес. `history` — 'push' | 'replace'. */
  function commit(next, history) {
    const prev = nav;
    nav = next;
    // Вопрос об уходе был про раздел prev.view: ушли из него другим путём
    // (например, в поиск, который не спрашивает) — вопрос снят.
    if (next.view !== prev.view) pending = null;
    const url = buildUrl(next);
    // Адрес не изменился — записи истории не плодим (например, изменилось
    // лишь то, что в адрес не пишется).
    if (url !== currentUrl()) {
      if (history === 'push') index += 1;
      window.history[history === 'push' ? 'pushState' : 'replaceState'](entry(), '', url);
    }
    remember(prev, next);
    emit();
  }

  function navigate(updater, history) {
    const next = updater(nav);
    if (next === nav) return;
    if (next.view !== nav.view && !canLeave(nav, next)) {
      pending = { updater, history, view: next.view };
      emit();
      return;
    }
    commit(next, history);
  }

  /**
   * Состояние для перехода в раздел `view`: при смене раздела раскладка
   * панелей — запомненная для него, а не та, что была в разделе-источнике
   * (иначе она утекла бы в адрес и в память нового раздела).
   */
  const entering = (prev, view) => (prev.view === view ? prev : { ...prev, ...readPanelState(view) });

  /** Переход: новая запись в истории. */
  const push = (updater) => navigate(updater, 'push');
  /** Не переход: адрес обновляется на месте. */
  const replace = (updater) => navigate(updater, 'replace');

  const api = {
    /**
     * Переключить верхнеуровневый view (вкладка / страница).
     * Восстанавливает последний открытый ресурс раздела (чат / документ / путь)
     * и запомненную для него раскладку панелей.
     */
    switchView(view) {
      push((prev) => {
        if (prev.view === view) return prev;
        const next = { ...entering(prev, view), view };
        if (view === 'chat') next.chatId = prev.chatId || memory.chatId || null;
        if (view === 'knowledge' && !prev.docId && !prev.search && memory.docId) next.docId = memory.docId;
        if (view === 'files' && !prev.filePath) {
          next.filePath = memory.filePath || '';
          next.fileProject = memory.fileProject || '';
          // Восстановленный репозиторий может быть не тем, в котором стояла
          // ревизия, — она принадлежит своему (см. nextFileRev).
          next.fileRev = nextFileRev(prev, next.fileProject, undefined);
        }
        return next;
      });
    },

    /**
     * Открыть документ в KB (из чата, doc-ссылки, дерева, карточки поиска).
     *
     * `find` и `section` приносит только переход из поиска: запрос подсветится в
     * документе, а раздел скажет find-бару, с какого совпадения начать. Открытый
     * иначе документ их не наследует — подсвечивать в нём нечего.
     */
    openDoc(docId, { find, section } = {}) {
      const id = docId == null ? null : String(docId);
      const docFind = find || '';
      push((prev) => ({
        ...entering(prev, 'knowledge'),
        view: 'knowledge',
        docId: id,
        docFind,
        docSection: docFind ? section || '' : '',
        search: '',
        mode: prev.mode,
      }));
    },

    /** Запустить поиск в KB (сбрасывает выбранный документ). */
    setSearch(search, mode) {
      push((prev) => ({
        ...entering(prev, 'knowledge'),
        view: 'knowledge',
        docId: search ? null : prev.docId, // при активном поиске документ не выбран
        search,
        mode: mode ?? prev.mode,
      }));
    },

    /**
     * Запустить единый поиск (`/search`).
     *
     * Это ПЕРЕХОД: раздел меняется, и «Назад» обязано вернуть туда, откуда искали.
     * Категорию называет вызывающий — по умолчанию она равна разделу, из которого
     * запустили поиск (см. scopeForView), а не последней выбранной: человек ищет
     * то, на что смотрит.
     *
     * По той же причине поиск из «Файлов» наследует область, которую там
     * смотрят, — репозиторий и ревизию (см. searchWhereFilesAre).
     */
    openSearch(query, scope) {
      push((prev) => ({
        ...entering(prev, 'search'),
        view: 'search',
        searchQuery: query || '',
        searchScope: normalizeScope(scope || prev.searchScope),
        ...searchWhereFilesAre(prev),
      }));
    },

    /**
     * Сменить категорию или её фильтры, не трогая запрос.
     *
     * Не переход: это уточнение одного и того же поиска — новый ресурс не
     * открывается, — и подбор фильтров не должен стоить пользователю по нажатию
     * «Назад» за каждую снятую галочку. Сам поиск как переход уже записан
     * openSearch.
     */
    refineSearch(patch) {
      replace((prev) => {
        const next = { ...prev, ...patch };
        if (patch.searchScope) next.searchScope = normalizeScope(patch.searchScope);
        // Поле покинули, не тронув, — состояние не двигаем.
        return Object.keys(patch).some((key) => next[key] !== prev[key]) ? next : prev;
      });
    },

    /**
     * Открыть путь в файловом браузере ('' — корень репозитория).
     *
     * Это один переход целиком — раздел вместе с путём: ссылка на файл из
     * поиска, чата или базы знаний зовёт только его, без отдельного
     * переключения раздела.
     *
     * @param project репозиторий пути; не передан — остаёмся в том, что открыт
     *   (клик по дереву не должен уводить в другой проект), а переход по ссылке из
     *   чата проект называет и панель переключает
     * @param options `{ changes, commit, rev, find, findRegex, lines, backLines, right }` — `changes`:
     *   каким показать левый блок (ссылка из вкладки «Репозиторий» ведёт к
     *   незакоммиченному, ссылка на файл — в дерево; не передан — режим остаётся
     *   тем, что был); `commit`: файл открыт из ленты коммитов — в центре его
     *   изменение в этом коммите, а лента остаётся слева; `find`: что подсветить в открытом файле — его приносит
     *   переход из поиска; `lines`: какие строки выделить (`42-45`, см.
     *   urlScheme.formatLines) — его приносит клик по ячейке blame; `backLines`:
     *   строки открытого сейчас файла, к которым вернёт «Назад» (ханк, по подписи
     *   которого кликнули), — пишутся в ТЕКУЩУЮ запись истории на месте, до
     *   перехода, так что запись по-прежнему одна; `right`: вкладка правой панели — ссылка на коммит
     *   открывает вкладку «Коммит» (не передан — раскладка раздела как была)
     */
    openFilePath(path, project, options) {
      // Не переход, а пометка места, с которого уходят: прокрутку внутреннего
      // блока браузер на «Назад» не восстановит, а выделение из адреса — да.
      if (options?.backLines) {
        const backLines = options.backLines;
        replace((prev) =>
          prev.view !== 'files' || prev.fileLines === backLines ? prev : { ...prev, fileLines: backLines },
        );
      }
      push((prev) => {
        const nextProject = project === undefined ? prev.fileProject : project || '';
        return {
          ...entering(prev, 'files'),
          view: 'files',
          filePath: path || '',
          fileProject: nextProject,
          // Режим левого блока — часть этого же перехода, а не отдельная запись:
          // отдельным setFileMode (он не переход) переход превратился бы в
          // замену, и «Назад» не вернуло бы туда, откуда ссылку нажали.
          fileMode: nextFileMode(prev, options),
          // Коммит, в котором смотрят изменение файла, принадлежит переходу из
          // ленты: любой другой переход открывает сам файл.
          fileCommit: options?.commit || '',
          fileRev: nextFileRev(prev, nextProject, options),
          // Подсветка принадлежит переходу, а не файлу: открыли файл откуда-то
          // ещё — искать в нём нечего, и прежний запрос красил бы случайное.
          fileFind: options?.find || '',
          fileFindRegex: !!options?.find && !!options?.findRegex,
          // Выделение строк — тоже переходу: номера принадлежат тому снимку, к
          // которому вела ссылка, в любом другом файле они значат случайное.
          fileLines: options?.lines || '',
          // Вкладка — тоже часть перехода: ссылка на коммит ведёт к его описанию,
          // и отдельный setRightTab стал бы второй записью истории.
          ...(options?.right === undefined ? {} : { rightTab: options.right || null }),
        };
      });
    },

    /**
     * Открыть/сменить активный чат.
     *
     * `navigate: false` — «чат выбран фоном, а не пользователем»: так ChatWindow
     * сообщает про автовыбор при загрузке (первый чат из списка, либо пустой
     * черновик, когда чатов нет). Панель чата смонтирована всегда, в том числе
     * когда открыт другой раздел, поэтому безусловный переход уводил бы с
     * /files/<путь> или /knowledge/doc/<id> на /chat сразу после старта — ссылкой
     * на файл или документ нельзя было бы поделиться. Выбор при этом запоминается
     * и попадает в адрес, как только пользователь вернётся в чат.
     *
     * Если открыт именно чат (просто /chat без id) — фоновый выбор всё же должен
     * попасть в адрес, иначе открытый чат нельзя скопировать ссылкой. Но это не
     * ПЕРЕХОД пользователя, поэтому пишем на месте: иначе автовыбор при каждой
     * свежей загрузке /chat плодил бы лишнюю запись истории (/chat → /chat/<id>),
     * на которой возврат «Назад» показал бы /chat без чата — и тут же снова
     * автовыбор: кнопка выглядела бы нерабочей.
     */
    openChat(chatId, { navigate = true, find, msg } = {}) {
      const id = chatId == null ? null : String(chatId);
      // Запрос относится к тому чату, из-за которого сюда пришли: открывая
      // другой, его не тащим — подсвечивать в нём нечего. Сообщение — тем более:
      // оно из этого чата, и в соседнем такого id либо нет, либо он чужой.
      const chatFind = find || '';
      const chatMsg = chatFind ? (msg == null ? '' : String(msg)) : '';
      if (!navigate) {
        // Состояние чата меняется и в другом разделе: адрес его там не пишет,
        // но возврат в чат берёт chatId из состояния, и черновик, ставший
        // настоящим чатом, пока открыты «Файлы», не должен вернуть /chat/new.
        replace((prev) =>
          prev.chatId === id && prev.chatFind === chatFind && prev.chatMsg === chatMsg
            ? prev
            : { ...prev, chatId: id, chatFind, chatMsg },
        );
        return;
      }
      push((prev) => ({ ...entering(prev, 'chat'), view: 'chat', chatId: id, chatFind, chatMsg }));
    },

    // ── Состояние экрана (не переход) ──────────────────────────────────────

    /** Свернуть/раскрыть левую панель текущего раздела. */
    toggleLeftPanel() {
      replace((prev) => ({ ...prev, leftCollapsed: !prev.leftCollapsed }));
    },

    /**
     * Раскрыть правую панель на вкладке `tab`, либо свернуть её (`null`).
     * Сеттер намеренно НЕ переключающий: панель раскрывают не только кликом по
     * тумблеру, но и действия (загрузили вложение → показать вложения), и для них
     * «повторный вызов сворачивает» дало бы ровно обратный эффект. Свернуть можно
     * кнопкой в шапке панели (она передаёт null).
     */
    setRightTab(tab) {
      replace((prev) => (prev.rightTab === (tab || null) ? prev : { ...prev, rightTab: tab || null }));
    },

    /**
     * Режим левого блока файлового браузера (FILE_MODE): дерево, изменения или
     * лента коммитов. Открытый путь остаётся тем же, и переключение
     * туда-обратно не должно требовать двух «Назад». Выбранный в ленте коммит
     * с неё и уходит: в другом режиме открыт уже сам файл.
     */
    setFileMode(mode) {
      replace((prev) => (prev.fileMode === mode ? prev : { ...prev, fileMode: mode, fileCommit: '' }));
    },

    /**
     * Какую ревизию показывает файловый браузер: '' — рабочее дерево, иначе имя
     * ветки, тега или хеш. Путь остаётся тем же, меняется только снимок, и
     * возврат в рабочее дерево не должен стоить двух «Назад».
     */
    setFileRev(rev) {
      // Выделенные строки — номера в прежнем снимке; в другом они не те же строки.
      replace((prev) => (prev.fileRev === (rev || '') ? prev : { ...prev, fileRev: rev || '', fileLines: '' }));
    },

    /**
     * Колонка авторства строк у открытого файла. Файл тот же, и тумблер не
     * должен стоить «Назад»; переезжает на следующий открытый файл вместе с
     * ревизией (openFilePath), как режим изменений, — это способ смотреть
     * репозиторий, а не свойство одного файла.
     */
    setFileBlame(on) {
      replace((prev) => (prev.fileBlame === !!on ? prev : { ...prev, fileBlame: !!on }));
    },

    /**
     * Что подсвечено в открытом файле ('' — ничего, бар закрыт). Файл остаётся
     * тем же, и набранный в баре запрос не должен стоить «Назад» на каждую букву.
     */
    setFileFind(find, regex) {
      const next = find || '';
      replace((prev) =>
        prev.fileFind === next && prev.fileFindRegex === !!regex
          ? prev
          : { ...prev, fileFind: next, fileFindRegex: !!next && !!regex },
      );
    },

    /**
     * Что подсвечено в открытом чате — по тем же правилам, что и в файле. Другой
     * запрос снимает сообщение (как раздел у документа): оно относилось к
     * запросу, с которым пришли из поиска, а набранный в баре садится на самое
     * свежее совпадение, как везде. Тот же запрос сообщение оставляет — бар
     * фиксирует его по Enter и уходу фокуса, и это не переход к другому месту.
     */
    setChatFind(find) {
      const next = find || '';
      replace((prev) => (prev.chatFind === next ? prev : { ...prev, chatFind: next, chatMsg: '' }));
    },

    /**
     * Что подсвечено в открытом документе — по тем же правилам. Другой запрос
     * снимает раздел: тот относился к запросу, с которым пришли из поиска, а
     * набранный в баре начинает с первого совпадения, как везде. Тот же запрос
     * раздел оставляет.
     */
    setDocFind(find) {
      const next = find || '';
      replace((prev) => (prev.docFind === next ? prev : { ...prev, docFind: next, docSection: '' }));
    },

    /**
     * Документ удалён: ни адрес, ни возврат в раздел не должны вести на него —
     * иначе перезагрузка или клик по вкладке показали бы «Документ не найден».
     * Запись на месте, а не переход: пользователь никуда не уходил.
     */
    forgetDoc(docId) {
      const id = docId == null ? null : String(docId);
      if (id === null) return;
      if (memory.docId === id) memory.docId = null;
      replace((prev) => (prev.docId === id ? { ...prev, docId: null, docFind: '', docSection: '' } : prev));
    },

    // ── Отложенный переход ─────────────────────────────────────────────────

    /** Ответ «уйти»: отложенный переход проигрывается от текущего состояния. */
    confirmLeave() {
      if (!pending) return;
      if (pending.delta != null) {
        const { delta } = pending;
        pending = null;
        transit = 'replay';
        window.history.go(delta);
        emit();
        return;
      }
      const { updater, history } = pending;
      pending = null;
      const next = updater(nav);
      if (next === nav) emit();
      else commit(next, history);
    },

    /** Ответ «остаться»: отложенный переход забыт. */
    cancelLeave() {
      if (!pending) return;
      pending = null;
      emit();
    },
  };

  return {
    api,
    subscribe(cb) {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    getSnapshot: () => snapshot,
    /**
     * Первая запись адреса — только replaceState: на старте мы лишь канонизируем
     * то, что уже открыто (legacy-ссылка → новая схема), а не переходим куда-то.
     * Иначе «Назад» возвращал бы на исходный legacy-адрес, который тут же
     * канонизируется снова — кнопка выглядела бы сломанной.
     */
    canonicalize() {
      // Метка ставится и тогда, когда адрес уже канонический: без неё к этой
      // записи нельзя было бы вернуться «Вперёд» с вопросом.
      window.history.replaceState(entry(), '', buildUrl(nav));
      // Раскладка, с которой раздел открыли (в том числе принесённая ссылкой),
      // запоминается сразу: иначе первый же уход в другой раздел и возврат
      // подставили бы вместо неё запомненную ранее. Здесь, а не при создании
      // стора: стор создаётся в рендере, а это побочный эффект.
      savePanelState(nav.view, { leftCollapsed: nav.leftCollapsed, rightTab: nav.rightTab });
    },
    /** «Назад»/«Вперёд»: адрес уже сменил браузер, состояние читается из него. */
    onPopState(event) {
      if (transit === 'restore') {
        transit = null; // вернулись на запись, с которой ушли, — состояние то же
        return;
      }
      const state = event?.state;
      const target = state?.kbSession === session && Number.isInteger(state.kbIndex) ? state.kbIndex : null;
      const next = popNav();
      if (transit !== 'replay' && target !== null && target !== index && replacesDoc(next) && !canReplaceDoc()) {
        pending = { delta: target - index, view: next.view };
        transit = 'restore';
        window.history.go(index - target);
        emit();
        return;
      }
      transit = null;
      if (target !== null) index = target;
      const prev = nav;
      nav = next;
      // Вопрос про уход относился к записи, с которой ушли кнопкой браузера.
      pending = null;
      remember(prev, nav);
      emit();
    },
  };
}

/**
 * Область поиска по файлам, унаследованная от «Файлов»: репозиторий и ревизия,
 * открытые в панели. Пусто для поиска откуда угодно ещё.
 *
 * Ищут в том, на что смотрят: из снимка ветки — по снимку, а не по рабочему
 * дереву, где искомого может не быть вовсе (или найдётся не та версия строки).
 * Уже из поиска фильтры не трогаем — их выбрали руками.
 *
 * Репозиторий едет вместе с ревизией и отдельно от неё не имеет смысла: имя
 * ветки принадлежит своему репозиторию (ср. nextFileRev). Значения переносятся
 * как есть — пустое означает дефолтный репозиторий и рабочее дерево в обеих
 * схемах адреса.
 *
 * Со сменой репозитория уходит и маска пути: `backend/**` написали про прежний,
 * а в новом такого каталога может не быть вовсе — это ноль результатов без
 * объяснений. Сменой считается любое расхождение строк, в том числе дефолтный
 * репозиторий, названный в адресе явно (`project=kb` против пустого значения):
 * какой из них дефолтный, стор не знает — это ответ конфигурации, а не адреса
 * (сравнение с разрешённым выбором делает `SearchPanel`, которому конфигурация
 * доступна). Лишний раз снятая маска ошибается в безопасную сторону — покажет
 * лишнее, а не спрячет найденное; ревизия на том же сравнении в `nextFileRev`.
 */
function searchWhereFilesAre(prev) {
  if (prev.view !== 'files') return null;
  const where = { searchProject: prev.fileProject, searchRev: prev.fileRev };
  return prev.searchProject === prev.fileProject ? where : { ...where, searchPath: '' };
}

/**
 * Режим левого блока после перехода: `changes` его называет (ссылка на
 * незакоммиченное — изменения, ссылка на файл — дерево), без него режим тот же.
 */
function nextFileMode(prev, options) {
  if (options?.changes === undefined) return prev.fileMode;
  return options.changes ? FILE_MODE.CHANGES : FILE_MODE.TREE;
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
 * - переход по ссылке на незакоммиченные изменения (`changes: true`, вкладка
 *   «Репозиторий» чата): такие правки есть только у рабочего дерева, и панель,
 *   оставшись в снимке, показала бы вместо них изменения коммита. Переключатель
 *   «Изменения» внутри панели (setFileMode) — не переход, и ревизию он не
 *   трогает: в снимке этот режим показывает то, что поменял сам коммит.
 */
function nextFileRev(prev, nextProject, options) {
  if (options?.rev !== undefined) return options.rev || '';
  if (options?.changes) return '';
  return nextProject === prev.fileProject ? prev.fileRev : '';
}
