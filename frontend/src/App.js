import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import ChatWindow from './components/chatPanel/ChatWindow';
import KnowledgeBase from './components/knowledgeBasePanel/KnowledgeBase';
import ConfirmModal from './components/common/ConfirmModal';
import { isEditorDirty } from './components/knowledgeBasePanel/editorDirtyStore';
import useAppNavigation from './useAppNavigation';
import HeaderMenu from './components/common/HeaderMenu';
import GlobalSearch from './components/common/GlobalSearch';
import AdminPanel from './components/adminPanel/AdminPanel';
import SettingsPanel from './components/settingsPanel/SettingsPanel';
import './App.css';

function App() {
  const { t } = useTranslation();
  const { nav, switchView, openDoc, setDocTab, setSearch, openChat } = useAppNavigation();
  const view = nav.view; // 'chat' | 'knowledge' | 'admin' | 'settings'

  // ── Глобальная строка поиска (живёт в шапке вкладок, видна всегда) ──────────
  const [searchText, setSearchText] = useState(nav.search || '');
  const [searchMode, setSearchMode] = useState(nav.mode || 'hybrid');

  useEffect(() => {
    setSearchText(nav.search || '');
  }, [nav.search]);
  useEffect(() => {
    setSearchMode(nav.mode || 'hybrid');
  }, [nav.mode]);

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
          />
        </div>

        <div className={`app-tab-panel ${view === 'knowledge' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <KnowledgeBase
            isActive={view === 'knowledge'}
            docId={view === 'knowledge' ? nav.docId : null}
            docTab={nav.docTab}
            search={view === 'knowledge' ? nav.search : ''}
            mode={nav.mode}
            refreshSignal={refreshTick}
            onRefreshingChange={setKbRefreshing}
            onOpenDoc={openDoc}
            onTabChange={setDocTab}
            onSearch={setSearch}
            onNavigateToChat={openChat}
          />
        </div>

        {/* Admin / Settings — полноценные view со своим URL, монтируются по адресу */}
        {view === 'admin' && (
          <div className="app-tab-panel app-tab-panel--active">
            <AdminPanel />
          </div>
        )}
        {view === 'settings' && (
          <div className="app-tab-panel app-tab-panel--active">
            <SettingsPanel />
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
