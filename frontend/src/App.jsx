import { useState, useEffect, useEffectEvent, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import ChatWindow from './components/chatPanel/ChatWindow';
import KnowledgeBase from './components/knowledgeBasePanel/KnowledgeBase';
import FilesPanel from './components/filesPanel/FilesPanel';
import ConfirmModal from './components/common/modal/ConfirmModal';
import useAppNavigation from './useAppNavigation';
import useUnsavedViewGuard from './useUnsavedViewGuard';
import { registerFileNavigator } from './fileNavigationBus';
import HeaderMenu from './components/common/layout/HeaderMenu';
import GlobalSearch from './components/common/search/GlobalSearch';
import AdminPanel from './components/adminPanel/AdminPanel';
import SettingsPanel from './components/settingsPanel/SettingsPanel';
import { SEARCH_MODE } from './constants/searchMode';
import { invalidateDocPreviewCache } from './components/common/preview/useDocPreview';
import { invalidateFilePreviewCache } from './components/common/preview/useFilePreview';
import { invalidatePath as invalidateFileTreePath } from './components/filesPanel/fileTreeStore';
import './App.css';

// Вкладки-иконки в левой зоне шапки. Подпись одна на кнопку: она же
// всплывающая подсказка, она же имя для скринридера.
const TABS = [
  { view: 'chat', icon: '💬', labelKey: 'nav.chats' },
  { view: 'knowledge', icon: '📚', labelKey: 'nav.knowledgeBase' },
  { view: 'files', icon: '📁', labelKey: 'nav.files' },
];

function App() {
  const { t } = useTranslation();
  const { nav, switchView, openDoc, setSearch, openChat, openFilePath, toggleLeftPanel, setRightTab } =
    useAppNavigation();
  const view = nav.view; // 'chat' | 'knowledge' | 'files' | 'admin' | 'settings'

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
  const [searchText, setSearchText] = useState(nav.search || '');
  const [searchMode, setSearchMode] = useState(nav.mode || SEARCH_MODE.HYBRID);

  // Поле — локальный черновик, но URL меняется и снаружи (кнопка «назад»,
  // открытая ссылка), и тогда черновик надо подтянуть. Подстройка идёт прямо в
  // рендере, а не в эффекте: эффект дал бы второй проход рендера на каждую
  // навигацию, а App держит смонтированными все разделы сразу.
  const [prevNavSearch, setPrevNavSearch] = useState(nav.search);
  if (prevNavSearch !== nav.search) {
    setPrevNavSearch(nav.search);
    setSearchText(nav.search || '');
  }
  const [prevNavMode, setPrevNavMode] = useState(nav.mode);
  if (prevNavMode !== nav.mode) {
    setPrevNavMode(nav.mode);
    setSearchMode(nav.mode || SEARCH_MODE.HYBRID);
  }

  // Поиск всегда уводит в базу знаний (setSearch выставляет view=knowledge),
  // поэтому отдельно «закрывать» admin/settings не нужно.
  const submitSearch = () => {
    setSearch(searchText.trim(), searchMode);
  };

  const handleSearchModeChange = (m) => {
    setSearchMode(m);
    if (searchText.trim()) setSearch(searchText.trim(), m);
  };

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
      invalidateFileTreePath(project, ref.path);
    });
    // Тик безвреден, даже если Files сейчас не смонтирована (проп просто не
    // используется) — а если смонтирована на том же пути, форсирует живой
    // рефетч вместо ожидания следующего открытия вкладки.
    setFilesRefreshTick((n) => n + 1);
  }, []);

  // Уход из KB с несохранёнными правками спрашивает подтверждение — переключаем
  // разделы через goView, а не через switchView напрямую.
  const { goView, pendingView, confirmLeave, cancelLeave } = useUnsavedViewGuard({ view, switchView });

  // Регистрируем переход в Files для DocLinkTooltip (кнопка "Открыть" у
  // файловой ссылки) — компонент смонтирован в чате/KB, на много уровней
  // ниже App, поэтому проп сюда не прокинуть без прошивки всей цепочки
  // (Message/ChatWindow, MarkdownEditor/DetailModals/...).
  //
  // Тело — useEffectEvent: обработчик живёт в модуле сколько угодно долго и
  // обязан видеть свежие goView/openFilePath, но перерегистрировать его на
  // каждое их изменение незачем.
  const navigateToFile = useEffectEvent((path, project) => {
    goView('files');
    openFilePath(path, project);
  });
  useEffect(() => registerFileNavigator((path, project) => navigateToFile(path, project)), []);

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
        <GlobalSearch
          value={searchText}
          mode={searchMode}
          onChange={setSearchText}
          onModeChange={handleSearchModeChange}
          onSubmit={submitSearch}
        />

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
            onNavigateToDoc={openDoc}
            onDocChanged={handleDocChanged}
            onFileChanged={handleFileChanged}
            panels={panels}
          />
        </div>

        <div className={`app-tab-panel ${view === 'knowledge' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <KnowledgeBase
            docId={view === 'knowledge' ? nav.docId : null}
            search={view === 'knowledge' ? nav.search : ''}
            mode={nav.mode}
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
              onPathChange={openFilePath}
              refreshToken={filesRefreshTick}
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
