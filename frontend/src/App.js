import React, { useState } from 'react';
import ChatWindow from './components/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import './App.css';

function getInitialTab() {
  const params = new URLSearchParams(window.location.search);
  if (params.get('doc') || params.get('search')) return 'knowledge';
  return 'chat';
}

function App() {
  const [activeTab, setActiveTab] = useState(getInitialTab); // 'chat' или 'knowledge'

  return (
    <div className="App">
      <div className="app-tabs">
        <button className={activeTab === 'chat' ? 'active' : ''} onClick={() => setActiveTab('chat')}>
          💬 Чаты
        </button>
        <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => setActiveTab('knowledge')}>
          📚 База знаний
        </button>
      </div>
      <main>{activeTab === 'chat' ? <ChatWindow /> : <KnowledgeBase />}</main>
    </div>
  );
}

export default App;
