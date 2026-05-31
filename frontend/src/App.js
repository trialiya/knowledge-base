import React, { useState, useEffect } from 'react';
import ChatWindow from './components/chatPanel/ChatWindow';
import KnowledgeBase from './components/knowledgeBasePanel/KnowledgeBase';
import ConfirmModal from './components/common/ConfirmModal';
import { isEditorDirty } from './components/knowledgeBasePanel/editorDirtyStore';
import { IconRefresh } from './components/knowledgeBasePanel/icons';
import useAppNavigation from './useAppNavigation';
import './App.css';

function App() {
  const { nav, switchView, openDoc, setDocTab, setSearch, openChat } = useAppNavigation();
  const activeTab = nav.view;

  // ── Глобальная строка поиска (живёт в шапке вкладок, видна всегда) ──────────
  const [searchText, setSearchText] = useState(nav.search || '');
  const [searchMode, setSearchMode] = useState(nav.mode || 'hybrid');

  useEffect(() => {
    setSearchText(nav.search || '');
  }, [nav.search]);
  useEffect(() => {
    setSearchMode(nav.mode || 'hybrid');
  }, [nav.mode]);

  const submitSearch = () => {
    setSearch(searchText.trim(), searchMode);
  };

  const handleSearchModeChange = (e) => {
    const m = e.target.value;
    setSearchMode(m);
    if (searchText.trim()) setSearch(searchText.trim(), m);
  };

  // ── Кнопка «Обновить» в шапке вкладок ──────────────────────────────────────
  // Действие refresh живёт в useKnowledgeBase. Триггерим его через тик-счётчик,
  // а статус refreshing получаем обратно колбэком (для спиннера/блокировки).
  // Кнопка видна только при открытом документе/папке (nav.docId на knowledge-view).
  const [refreshTick, setRefreshTick] = useState(0);
  const [kbRefreshing, setKbRefreshing] = useState(false);
  const showRefresh = activeTab === 'knowledge' && !!nav.docId;

  // ── Unsaved-changes guard при уходе из KB в чат ────────────────────────────
  const [pendingChatSwitch, setPendingChatSwitch] = useState(false);

  const handleSwitchView = (view) => {
    if (view === 'chat' && nav.view === 'knowledge' && isEditorDirty()) {
      setPendingChatSwitch(true);
      return;
    }
    switchView(view);
  };

  return (
    <div className="App">
      <div className="app-tabs">
        {/* Левая зона — вкладки */}
        <div className="app-tabs__left">
          <button className={activeTab === 'chat' ? 'active' : ''} onClick={() => handleSwitchView('chat')}>
            💬 Чаты
          </button>
          <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => handleSwitchView('knowledge')}>
            📚 База знаний
          </button>
        </div>

        {/* Центр — глобальный поиск по базе знаний */}
        <div className="app-search-row">
          <input
            type="text"
            placeholder="Поиск по базе знаний... (Enter)"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submitSearch();
            }}
          />
          <select value={searchMode} onChange={handleSearchModeChange}>
            <option value="hybrid">Гибридный</option>
            <option value="semantic">Семантический</option>
            <option value="keyword">По словам</option>
          </select>
        </div>

        {/* Правая зона — обновление (только при просмотре документа) */}
        <div className="app-tabs__right">
          {showRefresh && (
            <button
              className={`app-refresh-btn${kbRefreshing ? ' app-refresh-btn--spinning' : ''}`}
              onClick={() => setRefreshTick((t) => t + 1)}
              disabled={kbRefreshing}
              title="Обновить"
            >
              <IconRefresh size={15} />
            </button>
          )}
        </div>
      </div>

      <main>
        {/*
          Обе панели смонтированы всегда, скрыты через CSS — сохраняем скролл,
          загруженные данные, состояние стриминга и т.д.
        */}
        <div className={`app-tab-panel ${activeTab === 'chat' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <ChatWindow
            isActive={activeTab === 'chat'}
            activeChatId={nav.chatId}
            onSelectChat={openChat}
            onNavigateToDoc={openDoc}
          />
        </div>

        <div
          className={`app-tab-panel ${activeTab === 'knowledge' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}
        >
          <KnowledgeBase
            isActive={activeTab === 'knowledge'}
            docId={nav.view === 'knowledge' ? nav.docId : null}
            docTab={nav.docTab}
            search={nav.view === 'knowledge' ? nav.search : ''}
            mode={nav.mode}
            refreshSignal={refreshTick}
            onRefreshingChange={setKbRefreshing}
            onOpenDoc={openDoc}
            onTabChange={setDocTab}
            onSearch={setSearch}
            onNavigateToChat={openChat}
          />
        </div>
      </main>

      {/* ── Unsaved-changes warning when leaving KB for Chat ── */}
      <ConfirmModal
        open={pendingChatSwitch}
        icon="✏️"
        title="Несохранённые изменения"
        message="В редакторе базы знаний есть несохранённые изменения. Перейти в чат? Они останутся в редакторе, но не будут сохранены."
        confirmLabel="Перейти в чат"
        cancelLabel="Остаться"
        onConfirm={() => {
          setPendingChatSwitch(false);
          switchView('chat');
        }}
        onCancel={() => setPendingChatSwitch(false)}
      />
    </div>
  );
}

export default App;
