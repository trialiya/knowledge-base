import React, { useState } from 'react';
import ChatWindow from './components/chatPanel/ChatWindow';
import KnowledgeBase from './components/knowledgeBasePanel/KnowledgeBase';
import ConfirmModal from './components/common/ConfirmModal';
import { isEditorDirty } from './components/knowledgeBasePanel/editorDirtyStore';
import useAppNavigation from './useAppNavigation';
import './App.css';

function App() {
  const { nav, switchView, openDoc, setDocTab, setSearch, openChat } = useAppNavigation();
  const activeTab = nav.view;

  // When leaving the Knowledge Base for the Chat tab while the markdown editor
  // has unsaved changes, ask for confirmation first. Both panels stay mounted
  // (hidden via CSS), so the edits are NOT lost on switch — we therefore keep
  // the dirty flag and don't discard anything; the prompt is just a heads-up.
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
        <button className={activeTab === 'chat' ? 'active' : ''} onClick={() => handleSwitchView('chat')}>
          💬 Чаты
        </button>
        <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => handleSwitchView('knowledge')}>
          📚 База знаний
        </button>
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
