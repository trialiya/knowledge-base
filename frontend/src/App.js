import React, { useState, useCallback } from 'react';
import ChatWindow from './components/Chat/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import { getUrlState, setUrlTab } from './components/Utils/utils';
import './App.css';

function getInitialTab() {
  const { tab, docId, searchQuery } = getUrlState();
  // Explicit tab param takes priority
  if (tab === 'chat' || tab === 'knowledge') return tab;
  // Infer from other URL params
  if (docId || searchQuery) return 'knowledge';
  return 'chat';
}

function App() {
  const [activeTab, setActiveTab] = useState(getInitialTab);

  const switchTab = useCallback((tab) => {
    setActiveTab(tab);
    setUrlTab(tab);
  }, []);

  /**
   * Cross-component navigation: ChatWindow can call this to jump to a
   * specific document in the Knowledge Base (and vice versa in the future).
   */
  const navigateToDoc = useCallback((docId) => {
    setActiveTab('knowledge');
    // KnowledgeBase will pick up the doc param from the URL
    const params = new URLSearchParams(window.location.search);
    params.set('view', 'knowledge');
    params.set('doc', docId);
    params.delete('search');
    const url = `?${params.toString()}`;
    window.history.pushState({}, '', url);
    // Dispatch a custom event so KnowledgeBase can react without remounting
    window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId } }));
  }, []);

  const navigateToChat = useCallback((chatId) => {
    setActiveTab('chat');
    const params = new URLSearchParams(window.location.search);
    params.set('view', 'chat');
    if (chatId) {
      params.set('chat', chatId);
    } else {
      params.delete('chat');
    }
    params.delete('doc');
    params.delete('search');
    const url = `?${params.toString()}`;
    window.history.pushState({}, '', url);
    window.dispatchEvent(new CustomEvent('app:navigate-chat', { detail: { chatId } }));
  }, []);

  return (
    <div className="App">
      <div className="app-tabs">
        <button className={activeTab === 'chat' ? 'active' : ''} onClick={() => switchTab('chat')}>
          💬 Чаты
        </button>
        <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => switchTab('knowledge')}>
          📚 База знаний
        </button>
      </div>

      <main>
        {/*
          Both components stay mounted — hidden via CSS.
          This preserves scroll position, loaded data, streaming state, etc.
        */}
        <div className={`app-tab-panel ${activeTab === 'chat' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}>
          <ChatWindow onNavigateToDoc={navigateToDoc} isActive={activeTab === 'chat'} />
        </div>
        <div
          className={`app-tab-panel ${activeTab === 'knowledge' ? 'app-tab-panel--active' : 'app-tab-panel--hidden'}`}
        >
          <KnowledgeBase onNavigateToChat={navigateToChat} />
        </div>
      </main>
    </div>
  );
}

export default App;
