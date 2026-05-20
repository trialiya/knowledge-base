import React, { useState } from 'react';
import ChatWindow from './components/Chat/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import { getUrlState, setChatUrlState } from './components/KnowledgeBase/utils';
import './App.css';

function getInitialTab() {
  const { docId, searchQuery } = getUrlState();
  if (docId || searchQuery) return 'knowledge';
  // chatId или пустой URL → чат
  return 'chat';
}

function App() {
  const [activeTab, setActiveTab] = useState(getInitialTab);

  const switchTab = (tab) => {
    setActiveTab(tab);
    if (tab === 'chat') {
      // Восстановим chat id из localStorage, если есть
      const chatId = localStorage.getItem('chat_activeId');
      setChatUrlState(chatId);
    }
    // При переключении на knowledge — KnowledgeBase сам выставит свои параметры
  };

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
      <main>{activeTab === 'chat' ? <ChatWindow /> : <KnowledgeBase />}</main>
    </div>
  );
}

export default App;
