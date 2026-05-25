import React, { useState, useCallback, useEffect } from 'react';
import ChatWindow from './components/Chat/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import { getUrlState, setUrlTab, setKBUrlState, setChatUrlState } from './components/Utils/utils';
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

  // Синхронизация состояния с URL при навигации браузера (кнопки «Назад»/«Вперёд»).
  // Без этого pushState из navigateToDoc/navigateToChat/setUrlTab меняет URL,
  // но React-состояние остаётся прежним, и «Назад» визуально не работает.
  useEffect(() => {
    // На старте гарантируем, что текущая запись истории несёт явный view —
    // иначе первый «Назад» после перехода вести некуда (нет исходной записи).
    const { tab: urlTab, docId: urlDoc, searchQuery: urlSearch } = getUrlState();
    if (urlTab !== 'chat' && urlTab !== 'knowledge') {
      const params = new URLSearchParams(window.location.search);
      const inferred = urlDoc || urlSearch ? 'knowledge' : 'chat';
      params.set('view', inferred);
      window.history.replaceState({}, '', `?${params.toString()}`);
    }

    const handlePopState = () => {
      const { tab, docId, chatId } = getUrlState();
      const nextTab = tab === 'chat' || tab === 'knowledge' ? tab : docId ? 'knowledge' : 'chat';

      setActiveTab(nextTab);

      // Переиспользуем существующий механизм синхронизации внутри вкладок:
      // KnowledgeBase слушает app:navigate-doc, ChatWindow — app:navigate-chat.
      if (nextTab === 'knowledge' && docId) {
        window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId } }));
      } else if (nextTab === 'chat' && chatId) {
        window.dispatchEvent(new CustomEvent('app:navigate-chat', { detail: { chatId } }));
      }
    };

    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, []);

  /**
   * Cross-component navigation: ChatWindow can call this to jump to a
   * specific document in the Knowledge Base (and vice versa in the future).
   */
  const navigateToDoc = useCallback((docId) => {
    setActiveTab('knowledge');
    // Запись URL идёт через общий commitUrl (с дедупликацией истории).
    // searchQuery/mode сбрасываются автоматически при наличии docId.
    setKBUrlState(docId, 'summary', '', '');
    // Dispatch a custom event so KnowledgeBase can react without remounting
    window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId } }));
  }, []);

  const navigateToChat = useCallback((chatId) => {
    setActiveTab('chat');
    setChatUrlState(chatId);
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
