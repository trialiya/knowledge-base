import React, { useState } from 'react';
import ChatWindow from './components/ChatWindow';
import KnowledgeBase from './components/KnowledgeBase/KnowledgeBase';
import './App.css';

function App() {
  const [activeTab, setActiveTab] = useState('chat'); // 'chat' или 'knowledge'

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
