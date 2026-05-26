import React from 'react';
import ChatWindow from './components/Chat/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import useAppNavigation from './useAppNavigation';
import './App.css';

function App() {
  const { nav, switchView, openDoc, setDocTab, setSearch, openChat } = useAppNavigation();
  const activeTab = nav.view;

  return (
    <div className="App">
      <div className="app-tabs">
        <button className={activeTab === 'chat' ? 'active' : ''} onClick={() => switchView('chat')}>
          💬 Чаты
        </button>
        <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => switchView('knowledge')}>
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
    </div>
  );
}

export default App;
