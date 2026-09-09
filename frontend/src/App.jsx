import { useState, useEffect, useEffectEvent, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import ChatWindow from '@/components/chatPanel/ChatWindow';
import KnowledgeBase from '@/components/knowledgeBasePanel/KnowledgeBase';
import FilesPanel from '@/components/filesPanel/FilesPanel';
import SearchPanel from '@/components/searchPanel/SearchPanel';
import ConfirmModal from '@/components/common/modal/ConfirmModal';
import useAppNavigation from '@/navigation/useAppNavigation';
import useUnsavedViewGuard from '@/navigation/useUnsavedViewGuard';
import { registerFileNavigator } from '@/navigation/fileNavigationBus';
import HeaderMenu from '@/components/common/layout/HeaderMenu';
import GlobalSearch from '@/components/common/search/GlobalSearch';
import AdminPanel from '@/components/adminPanel/AdminPanel';
import SettingsPanel from '@/components/settingsPanel/SettingsPanel';
import { scopeForView } from '@/constants/searchScope';
import { invalidateDocPreviewCache } from '@/components/common/preview/useDocPreview';
import { invalidateFilePreviewCache, invalidateAllFilePreviewCache } from '@/components/common/preview/useFilePreview';
import {
  invalidatePath as invalidateFileTreePath,
  invalidateFileListings,
  treeScope,
} from '@/components/filesPanel/fileTreeStore';
import '@/App.css';

// Вкладки-иконки в левой зоне шапки. Подпись одна на кнопку: она же
// всплывающая подсказка, она же имя для скринридера.
const TABS = [
  { view: 'chat', icon: '💬', labelKey: 'nav.chats' },
  { view: 'knowledge', icon: '📚', labelKey: 'nav.knowledgeBase' },
  { view: 'files', icon: '📁', labelKey: 'nav.files' },
];

function App() {
  const { t } = useTranslation();
  const {
    nav,
    switchView,
    openDoc,
    openSearch,
    refineSearch,
    setSearch,
    openChat,
    openFilePath,
    setFileChanges,
    setFileRev,
    setFileFind,
    setChatFind,
    setDocFind,
    toggleLeftPanel,
    setRightTab,
  } = useAppNavigation();
  const view = nav.view; // 'chat' | 'knowledge' | 'files' | 'search' | 'admin' | 'settings'

  // Раскладка панелей рабочей области. Живёт в URL (общая для всех разделов
  // пара left/right), поэтому передаётся разделам одним набором пропсов.
  // useMemo обязателен: чат смонтирован всегда, и новый объект на каждый рендер
  // App (ввод в строке поиска, тик refresh) перерисовывал бы все разделы разом.
  const panels = useMemo(
    () => ({
      leftCollapsed: nav.leftCollapsed,
      onToggleLeft: toggleLeftPanel,
      rightTab: nav.rightTab,
      onRightTabChange: setRightTab,
    }),
    [nav.leftCollapsed, nav.rightTab, toggleLeftPanel, setRightTab],
  );

  // ── Глобальная строка поиска (живёт в шапке вкладок, видна всегда) ──────────
  // В поле стоит последний запрос, который где-то отработан. В самом поиске это
  // его запрос; в базе знаний, открытой старой ссылкой на /knowledge/search, —
  // её собственный. В остальных разделах — всё равно последний искомый: уйдя по
  // ссылке из результатов, запрос хочется поправить, а не набирать заново.
  const navQuery = view === 'search' ? nav.searchQuery : nav.search || nav.searchQuery;
  const [searchText, setSearchText] = useState(navQuery || '');

  // Поле — локальный черновик, но URL меняется и снаружи (кнопка «назад»,
  // открытая ссылка), и тогда черновик надо подтянуть. Подстройка идёт прямо в
  // рендере, а не в эффекте: эффект дал бы второй проход рендера на каждую
  // навигацию, а App держит смонтированными все разделы сразу.
  const [prevNavQuery, setPrevNavQuery] = useState(navQuery);
  if (prevNavQuery !== navQuery) {
    setPrevNavQuery(navQuery);
    setSearchText(navQuery || '');
  }

  // Enter в строке поиска уводит в раздел «Поиск». Категория по умолчанию — та,
  // на что человек смотрел: из чата ищут по чатам, из базы знаний по документам.
  // Уже находясь в поиске, категорию не трогаем — её выбрали руками.
  //
  // Мимо goView намеренно: искать посреди правки документа — обычное дело, и
  // спрашивать про несохранённое на каждый Enter незачем. База знаний
  // смонтирована всегда, черновик её редактора переживает уход в поиск.
  const submitSearch = () => {
    openSearch(searchText.trim(), view === 'search' ? nav.searchScope : scopeForView(view));
  };

  // Фильтры единого поиска приходят разделу одним объектом — useMemo по той же
  // причине, что и panels: раздел не должен перерисовываться на каждую букву,
  // напечатанную в строке поиска.
  const searchFilters = useMemo(
    () => ({
      path: nav.searchPath,
      project: nav.searchProject,
      rev: nav.searchRev,
      regex: nav.searchRegex,
      untracked: nav.searchUntracked,
    }),
    [nav.searchPath, nav.searchProject, nav.searchRev, nav.searchRegex, nav.searchUntracked],
  );

  // ── Refresh документа (действие живёт в useKnowledgeBase) ────────────────────
  const [refreshTick, setRefreshTick] = useState(0);
  const [kbRefreshing, setKbRefreshing] = useState(false);
  const showRefresh = view === 'knowledge' && !!nav.docId;

  // ── Инвалидация Knowledge/Files по мутациям инструментов чата ───────────────
  // Чат смонтирован всегда (см. main ниже) и продолжает стримить события, даже
  // когда открыт другой раздел, поэтому App — единственное общее место, откуда
  // можно и дёрнуть модульный кэш файлового дерева, и толкнуть живое состояние
  // KB (она тоже смонтирована всегда, поэтому у неё нет своего "открытия
  // вкладки", на которое можно было бы повесить рефетч).
  const [docMutations, setDocMutations] = useState(null); // Array<{ id, parentId, action }> | null
  const [filesRefreshTick, setFilesRefreshTick] = useState(0);

  // refs — ВЕСЬ список мутаций одного TOOL_CALLS события (см. useChatEventStream):
  // один setState на событие, а не один на мутацию, иначе несколько setState подряд
  // в одном тике React 18 схлопнутся до последнего и KB увидит только последнюю
  // мутацию прогона — например, потеряет refreshScope для первого из двух doc'ов,
  // созданных в разных папках одним ответом ассистента.
  const handleDocChanged = useCallback((refs) => {
    refs.forEach((ref) => invalidateDocPreviewCache(ref.id));
    setDocMutations(
      refs.map((ref) => ({
        id: Number(ref.id),
        parentId: ref.parentId != null ? Number(ref.parentId) : null,
        action: ref.action,
      })),
    );
  }, []);

  const handleFileChanged = useCallback((refs, project) => {
    refs.forEach((ref) => {
      invalidateFilePreviewCache(project, ref.path);
      // Правка инструмента меняет рабочее дерево; снимки коммитов от неё не
      // сдвигаются — у них свой кэш и он остаётся верным.
      invalidateFileTreePath(treeScope(project, ''), ref.path);
    });
    // Тик безвреден, даже если Files сейчас не смонтирована (проп просто не
    // используется) — а если смонтирована на том же пути, форсирует живой
    // рефетч вместо ожидания следующего открытия вкладки.
    setFilesRefreshTick((n) => n + 1);
  }, []);

  /**
   * Команда git сдвинула рабочее дерево целиком: checkout,
   * stash, коммит или откат файла меняют сразу и дерево, и список изменений, и
   * содержимое открытого файла. Точечная инвалидация тут не подходит — какие
   * именно пути поменялись, знает только git, — поэтому листинги сбрасываются
   * целиком, одним тиком, тем же, что и после правки файла ассистентом.
   * Раскрытые каталоги при этом сохраняются: от коммита они не зависят.
   *
   * Сигнал общий для обеих поверхностей — панели «Файлы» и панели чата: дерево
   * у них одно, и pull, сделанный из чата, обязан дойти до открытого файла так
   * же, как сделанный из файлов.
   */
  const handleRepoChanged = useCallback(() => {
    invalidateFileListings();
    invalidateAllFilePreviewCache();
    setFilesRefreshTick((n) => n + 1);
  }, []);

  /**
   * `fetch` — единственная команда, ничего в рабочем дереве не меняющая: она
   * двигает только remote-tracking refs, то есть счётчики «впереди/позади».
   * Свой сигнал, а не общий, потому что общий перезапросил бы заодно дерево,
   * изменения и содержимое открытого файла, которых fetch не касается.
   *
   * И всё же общий на обе поверхности: строк ветки в приложении две — в панели
   * «Файлы» и во вкладке «Репозиторий» чата, — а репозиторий один, и счётчик,
   * подвинувшийся в одной, обязан подвинуться в другой.
   */
  const [gitRefsTick, setGitRefsTick] = useState(0);
  const handleGitRefsChanged = useCallback(() => setGitRefsTick((n) => n + 1), []);

  // Уход из KB с несохранёнными правками спрашивает подтверждение — переключаем
  // разделы через goView, а не через switchView напрямую.
  const { goView, pendingView, confirmLeave, cancelLeave } = useUnsavedViewGuard({ view, switchView });

  // Ссылка на файл в результатах поиска ведёт в «Файлы»: раздел меняется вместе
  // с открытым путём, одним переходом — его и делает `openFilePath`, сам ставя
  // раздел. Переключать раздел перед ним нечем: `goView` спрашивает про
  // несохранённые правки, а уходят здесь из поиска, не из базы знаний.
  const openFileFromSearch = openFilePath;

  // Регистрируем переход в Files для DocLinkTooltip (кнопка "Открыть" у
  // файловой ссылки) — компонент смонтирован в чате/KB, на много уровней
  // ниже App, поэтому проп сюда не прокинуть без прошивки всей цепочки
  // (Message/ChatWindow, MarkdownEditor/DetailModals/...).
  //
  // Тело — useEffectEvent: обработчик живёт в модуле сколько угодно долго и
  // обязан видеть свежие goView/openFilePath, но перерегистрировать его на
  // каждое их изменение незачем.
  const navigateToFile = useEffectEvent((path, project, options) => {
    goView('files');
    // Режим левого блока едет тем же переходом: ссылка на файл из чата ведёт в
    // дерево, ссылка из вкладки «Репозиторий» — к незакоммиченному.
    openFilePath(path, project, options);
  });
  useEffect(() => registerFileNavigator((path, project, options) => navigateToFile(path, project, options)), []);

  return (
    <div className="App">
      <div className="app-tabs">
        {/* Левая зона — вкладки-иконки (подпись во всплывающей подсказке) */}
        <div className="app-tabs__left">
          {TABS.map((tab) => (
            <button
              key={tab.view}
              className={`app-tab-icon${view === tab.view ? ' app-tab-icon--active' : ''}`}
              onClick={() => goView(tab.view)}
              aria-label={t(tab.labelKey)}
              data-tooltip={t(tab.labelKey)}
            >
              <span aria-hidden="true">{tab.icon}</span>
            </button>
          ))}
        </div>

        {/* Центр — глобальный поиск по базе знаний */}
        <GlobalSearch value={searchText} onChange={setSearchText} onSubmit={submitSearch} />

        {/* Правая зона — единое меню (обновить · язык · админ · настройки) */}
        <div className="app-tabs__right">
          <HeaderMenu
            showRefresh={showRefresh}
            refreshing={kbRefreshing}
            onRefresh={() => setRefreshTick((n) => n + 1)}
            onOpenAdmin={() => goView('admin')}
            onOpenSettings={() => goView('settings')}
          />
        </div>
      </div>

      <main>
        {/* Чат и База знаний смонтированы всегда, скрыты через CSS */}
        <div className={`app-tab-panel ${view === 'chat' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <ChatWindow
            isActive={view === 'chat'}
            activeChatId={nav.chatId}
            onSelectChat={openChat}
            find={view === 'chat' ? nav.chatFind : ''}
            msg={view === 'chat' ? nav.chatMsg : ''}
            onFindChange={setChatFind}
            onNavigateToDoc={openDoc}
            onDocChanged={handleDocChanged}
            onFileChanged={handleFileChanged}
            filesRefreshToken={filesRefreshTick}
            gitRefsToken={gitRefsTick}
            onRepoChanged={handleRepoChanged}
            onGitRefsChanged={handleGitRefsChanged}
            panels={panels}
          />
        </div>

        <div className={`app-tab-panel ${view === 'knowledge' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <KnowledgeBase
            isActive={view === 'knowledge'}
            docId={view === 'knowledge' ? nav.docId : null}
            search={view === 'knowledge' ? nav.search : ''}
            mode={nav.mode}
            find={view === 'knowledge' ? nav.docFind : ''}
            section={view === 'knowledge' ? nav.docSection : ''}
            onFindChange={setDocFind}
            refreshSignal={refreshTick}
            onRefreshingChange={setKbRefreshing}
            onOpenDoc={openDoc}
            onSearch={setSearch}
            mutatedDocs={docMutations}
            panels={panels}
          />
        </div>

        {/* Files / Admin / Settings — полноценные view со своим URL, монтируются по адресу */}
        {view === 'files' && (
          <div className="app-tab-panel app-tab-panel--active">
            <FilesPanel
              project={nav.fileProject}
              path={nav.filePath}
              changes={nav.fileChanges}
              rev={nav.fileRev}
              find={nav.fileFind}
              findRegex={nav.fileFindRegex}
              onChangesToggle={setFileChanges}
              onRevChange={setFileRev}
              onFindChange={setFileFind}
              onPathChange={openFilePath}
              refreshToken={filesRefreshTick}
              gitRefsToken={gitRefsTick}
              onRepoChanged={handleRepoChanged}
              onGitRefsChanged={handleGitRefsChanged}
              panels={panels}
            />
          </div>
        )}
        {view === 'search' && (
          <div className="app-tab-panel app-tab-panel--active">
            <SearchPanel
              query={nav.searchQuery}
              scope={nav.searchScope}
              mode={nav.mode}
              filters={searchFilters}
              onRefine={refineSearch}
              onOpenFile={openFileFromSearch}
              onOpenDoc={openDoc}
              onOpenChat={openChat}
              panels={panels}
            />
          </div>
        )}
        {view === 'admin' && (
          <div className="app-tab-panel app-tab-panel--active">
            <AdminPanel panels={panels} />
          </div>
        )}
        {view === 'settings' && (
          <div className="app-tab-panel app-tab-panel--active">
            <SettingsPanel panels={panels} />
          </div>
        )}
      </main>

      {/* ── Unsaved-changes warning when leaving KB ── */}
      <ConfirmModal
        open={!!pendingView}
        icon="✏️"
        title={t('unsaved.title')}
        message={t('unsaved.message')}
        confirmLabel={t('unsaved.confirm')}
        cancelLabel={t('unsaved.cancel')}
        onConfirm={confirmLeave}
        onCancel={cancelLeave}
      />
    </div>
  );
}

export default App;
