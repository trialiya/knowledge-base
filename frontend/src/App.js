import React, { useState, useCallback, useEffect, useRef } from 'react';
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

  // Последний документ, открытый в «Базе знаний». Хранится в памяти (не в URL),
  // чтобы при переключении на чат URL оставался чистым (без doc), а клик по
  // вкладке «База знаний» снова открыл тот же документ.
  const lastDocIdRef = useRef(getUrlState().docId || null);

  const switchTab = useCallback((tab) => {
    setActiveTab(tab);
    if (tab === 'knowledge') {
      if (lastDocIdRef.current) {
        // Восстанавливаем последний прочитанный документ
        setKBUrlState(lastDocIdRef.current, 'summary', '', '');
        window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId: lastDocIdRef.current } }));
      } else {
        setUrlTab(tab);
      }
    } else {
      // Переход в чат: убираем KB-параметры из URL, чтобы история не смешивалась.
      // (ChatWindow затем уточнит chat= через свой эффект синхронизации.)
      setUrlTab(tab, { clearKb: true });
    }
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

      // Помним последний документ из истории, чтобы вкладка «База знаний»
      // открывала именно его.
      if (docId) lastDocIdRef.current = docId;

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

  // Следим за навигацией по документам ВНУТРИ «Базы знаний» (листание узлов,
  // клики по doc-ссылкам), чтобы lastDocIdRef всегда указывал на актуальный
  // документ. KnowledgeBase уже диспатчит app:navigate-doc при таких переходах.
  useEffect(() => {
    const handleNavigateDoc = (e) => {
      const id = e.detail?.docId;
      if (id) lastDocIdRef.current = String(id);
    };
    window.addEventListener('app:navigate-doc', handleNavigateDoc);
    return () => window.removeEventListener('app:navigate-doc', handleNavigateDoc);
  }, []);

  /**
   * Cross-component navigation: ChatWindow can call this to jump to a
   * specific document in the Knowledge Base (and vice versa in the future).
   */
  const navigateToDoc = useCallback((docId) => {
    setActiveTab('knowledge');
    lastDocIdRef.current = String(docId);
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
