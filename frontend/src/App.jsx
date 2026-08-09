import { useState, useEffect, useMemo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import ChatWindow from './components/chatPanel/ChatWindow';
import KnowledgeBase from './components/knowledgeBasePanel/KnowledgeBase';
import FilesPanel from './components/filesPanel/FilesPanel';
import ConfirmModal from './components/common/ConfirmModal';
import { isEditorDirty } from './components/knowledgeBasePanel/editorDirtyStore';
import useAppNavigation from './useAppNavigation';
import { registerFileNavigator } from './fileNavigationBus';
import HeaderMenu from './components/common/HeaderMenu';
import GlobalSearch from './components/common/GlobalSearch';
import AdminPanel from './components/adminPanel/AdminPanel';
import SettingsPanel from './components/settingsPanel/SettingsPanel';
import { SEARCH_MODE } from './constants/searchMode';
import { invalidateDocPreviewCache } from './components/common/useDocPreview';
import { invalidateFilePreviewCache } from './components/common/useFilePreview';
import { invalidatePath as invalidateFileTreePath } from './components/filesPanel/fileTreeStore';
import './App.css';

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

  const handleFileChanged = useCallback((refs) => {
    refs.forEach((ref) => {
      invalidateFilePreviewCache(ref.path);
      invalidateFileTreePath(ref.path);
    });
    // Тик безвреден, даже если Files сейчас не смонтирована (проп просто не
    // используется) — а если смонтирована на том же пути, форсирует живой
    // рефетч вместо ожидания следующего открытия вкладки.
    setFilesRefreshTick((n) => n + 1);
  }, []);

  // ── Unsaved-changes guard при уходе из KB в любой другой раздел ──────────────
  // pendingView помнит, КУДА хотел уйти пользователь, чтобы после подтверждения
  // перейти именно туда (chat / admin / settings), а не только в чат.
  const [pendingView, setPendingView] = useState(null);

  const goView = (target) => {
    if (view === 'knowledge' && target !== 'knowledge' && isEditorDirty()) {
      setPendingView(target);
      return;
    }
    switchView(target);
  };

  // Регистрируем переход в Files для DocLinkTooltip (кнопка "Открыть" у
  // файловой ссылки) — компонент смонтирован в чате/KB, на много уровней
  // ниже App, поэтому проп сюда не прокинуть без прошивки всей цепочки
  // (Message/ChatWindow, MarkdownEditor/DetailModals/...).
  useEffect(() => {
    registerFileNavigator((path) => {
      goView('files');
      openFilePath(path);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openFilePath]);

  return (
    <div className="App">
      <div className="app-tabs">
        {/* Левая зона — вкладки-иконки (подпись во всплывающей подсказке) */}
        <div className="app-tabs__left">
          <button
            className={`app-tab-icon${view === 'chat' ? ' app-tab-icon--active' : ''}`}
            onClick={() => goView('chat')}
            aria-label={t('nav.chats')}
            data-tooltip={t('nav.chats')}
          >
            <span aria-hidden="true">💬</span>
          </button>
          <button
            className={`app-tab-icon${view === 'knowledge' ? ' app-tab-icon--active' : ''}`}
            onClick={() => goView('knowledge')}
            aria-label={t('nav.knowledgeBase')}
            data-tooltip={t('nav.knowledgeBase')}
          >
            <span aria-hidden="true">📚</span>
          </button>
          <button
            className={`app-tab-icon${view === 'files' ? ' app-tab-icon--active' : ''}`}
            onClick={() => goView('files')}
            aria-label={t('nav.files')}
            data-tooltip={t('nav.files')}
          >
            <span aria-hidden="true">📁</span>
          </button>
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
        onConfirm={() => {
          const target = pendingView;
          setPendingView(null);
          switchView(target);
        }}
        onCancel={() => setPendingView(null)}
      />
    </div>
  );
}

export default App;
