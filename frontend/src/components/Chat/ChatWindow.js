import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';

import MessageList from './MessageList';
import MessageInput from './MessageInput';
import ChatList from './ChatList';
import AttachmentPanel, { IconPaperclip } from '../KnowledgeBase/AttachmentPanel';
import { getUrlState, setChatUrlState } from '../KnowledgeBase/utils';
import './ChatWindow.css';
import '../KnowledgeBase/AttachmentPanel.css';

const generateUUID = () => {
  if (crypto?.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
};

const DEFAULT_MESSAGE = {};
const STORAGE_KEY_ACTIVE_ID = 'chat_activeId';

const ChatWindow = () => {
  const [chats, setChats] = useState([]);
  const [activeChatId, setActiveChatId] = useState(() => {
    const { chatId } = getUrlState();
    return chatId || localStorage.getItem(STORAGE_KEY_ACTIVE_ID) || null;
  });
  const [isLoading, setIsLoading] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [attachPanelOpen, setAttachPanelOpen] = useState(false);
  const [attachCount, setAttachCount] = useState(0);
  const aiMessageTextRef = useRef('');
  const aiMessageIndexRef = useRef(-1);
  const abortControllerRef = useRef(null);
  const attachFileRef = useRef(null);
  // Ref to hold activeChatId at mount time so the initial fetch effect
  // doesn't need it in its dependency array (we only want this to run once).
  const initialActiveChatIdRef = useRef(activeChatId);
  // Mirror of `chats` so callbacks can read the latest value synchronously
  // without listing `chats` in their dependency arrays (which would recreate
  // them on every streaming chunk).
  const chatsRef = useRef(chats);
  useEffect(() => {
    chatsRef.current = chats;
  }, [chats]);

  // Загрузка списка чатов
  useEffect(() => {
    const fetchChats = async () => {
      try {
        const res = await fetch('/api/chat');
        if (!res.ok) throw new Error('Failed to fetch chats');
        const data = await res.json();
        const chatList = data.map((chat) => ({
          id: chat.conversationId,
          title: chat.topic || 'Новый чат',
          messages: null,
          createdAt: chat.createdAt || null,
        }));
        setChats(chatList);
        const currentId = initialActiveChatIdRef.current;
        if (!currentId || !chatList.find((c) => c.id === currentId)) {
          const firstId = chatList[0]?.id;
          if (firstId) {
            setActiveChatId(firstId);
            localStorage.setItem(STORAGE_KEY_ACTIVE_ID, firstId);
          }
        }
      } catch (err) {
        console.error('Ошибка загрузки списка чатов:', err);
      }
    };
    fetchChats();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Переименование чата
  const renameChat = useCallback(async (chatId, newTitle) => {
    try {
      const res = await fetch(`/api/chat/topic?conversationId=${encodeURIComponent(chatId)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: newTitle,
      });
      if (!res.ok) throw new Error('Failed to rename chat');
      setChats((prev) => prev.map((chat) => (chat.id === chatId ? { ...chat, title: newTitle } : chat)));
    } catch (err) {
      console.error('Ошибка переименования чата:', err);
    }
  }, []);

  // Фоновое обновление темы чата с бэкенда после ответа
  const fetchAndUpdateTitle = useCallback(async (chatId) => {
    try {
      const res = await fetch(`/api/chat/chat?conversationId=${encodeURIComponent(chatId)}`);
      if (!res.ok) return;
      const data = await res.json();
      const newTitle = data.topic;
      if (newTitle) {
        setChats((prev) => prev.map((chat) => (chat.id === chatId ? { ...chat, title: newTitle } : chat)));
      }
    } catch (err) {
      console.error('Ошибка обновления темы чата:', err);
    }
  }, []);

  // Загрузка сообщений
  const loadMessages = useCallback(async (chatId) => {
    setLoadingMessages(true);
    try {
      const res = await fetch(`/api/chat/chat?conversationId=${encodeURIComponent(chatId)}`);
      if (!res.ok) throw new Error('Failed to load messages');
      const data = await res.json();
      const msgs =
        data.messages?.map((msg) => ({
          text: msg.content,
          sender: msg.type?.toLowerCase?.() === 'user' ? 'user' : 'ai',
        })) || [];
      const finalMessages = msgs.length > 0 ? msgs : [{ ...DEFAULT_MESSAGE }];
      setChats((prev) => prev.map((chat) => (chat.id === chatId ? { ...chat, messages: finalMessages } : chat)));
    } catch (err) {
      console.error('Ошибка загрузки сообщений:', err);
      setChats((prev) =>
        prev.map((chat) => (chat.id === chatId ? { ...chat, messages: [{ ...DEFAULT_MESSAGE }] } : chat)),
      );
    } finally {
      setLoadingMessages(false);
    }
  }, []);

  useEffect(() => {
    if (activeChatId) {
      const chat = chats.find((c) => c.id === activeChatId);
      if (!chat?.messages) {
        loadMessages(activeChatId);
      }
      localStorage.setItem(STORAGE_KEY_ACTIVE_ID, activeChatId);
      setChatUrlState(activeChatId);
    }
  }, [activeChatId, chats, loadMessages]);

  const activeMessages = useMemo(() => chats.find((c) => c.id === activeChatId)?.messages || [], [chats, activeChatId]);
  const activeChat = useMemo(() => chats.find((c) => c.id === activeChatId) || null, [chats, activeChatId]);

  // Отправка сообщения
  const handleSendMessage = useCallback(
    async (text) => {
      if (!activeChatId) return;

      // Вычисляем индекс нового AI-сообщения СИНХРОННО, до setChats.
      // Updater в setChats выполняется асинхронно, поэтому нельзя
      // полагаться на значение, присвоенное внутри него.
      const currentChat = chatsRef.current.find((c) => c.id === activeChatId);
      const baseMessages = currentChat?.messages || [];
      // После добавления [user, ai] AI-сообщение будет последним.
      const initialAiIndex = baseMessages.length + 1;

      setChats((prev) => {
        const activeChat = prev.find((c) => c.id === activeChatId);
        if (!activeChat) return prev;
        const newMessages = [...(activeChat.messages || []), { text, sender: 'user' }, { text: '', sender: 'ai' }];
        const updatedChat = { ...activeChat, messages: newMessages };
        const otherChats = prev.filter((c) => c.id !== activeChatId);
        return [updatedChat, ...otherChats];
      });

      setIsLoading(true);
      aiMessageTextRef.current = '';
      aiMessageIndexRef.current = initialAiIndex;

      const abortController = new AbortController();
      abortControllerRef.current = abortController;

      try {
        const url = `/api/chat/stream?conversationId=${encodeURIComponent(activeChatId)}`;
        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: text,
          signal: abortController.signal,
        });

        if (!response.ok) throw new Error('Network response was not ok');

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            const trimmedLine = line.trim();
            if (trimmedLine.startsWith('data:')) {
              const jsonString = trimmedLine.slice(5).trim();
              if (jsonString === '[DONE]') {
                aiMessageTextRef.current = aiMessageTextRef.current.trimEnd();
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  const idx = aiMessageIndexRef.current;
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: aiMessageTextRef.current };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== activeChatId);
                  return [updated, ...others];
                });
                fetchAndUpdateTitle(activeChatId);
                setIsLoading(false);
                return;
              }

              if (!jsonString) continue;

              let textChanged = false;
              let shouldFinishSegment = false;
              let isDone = false;
              try {
                const parsed = JSON.parse(jsonString);
                // Бэкенд присылает текст в поле "message".
                if (parsed.message) {
                  aiMessageTextRef.current += parsed.message;
                  textChanged = true;
                }
                // Завершение всего ответа.
                if (parsed.finishReason === 'STOP') {
                  isDone = true;
                }
                // Граница сегмента (вызов инструмента) — начинаем новое
                // AI-сообщение, но только если в текущем уже есть текст.
                if (parsed.finishReason === 'TOOL_CALLS' && aiMessageTextRef.current.trim() !== '') {
                  shouldFinishSegment = true;
                }
              } catch {
                /* ignore parse errors */
              }

              if (isDone) {
                aiMessageTextRef.current = aiMessageTextRef.current.trimEnd();
                const finalText = aiMessageTextRef.current;
                const idx = aiMessageIndexRef.current;
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: finalText };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== activeChatId);
                  return [updated, ...others];
                });
                fetchAndUpdateTitle(activeChatId);
                setIsLoading(false);
                return;
              }

              if (shouldFinishSegment) {
                const finalText = aiMessageTextRef.current.trimEnd();
                aiMessageTextRef.current = '';
                const finishedIdx = aiMessageIndexRef.current;
                const newIdx = finishedIdx + 1;
                aiMessageIndexRef.current = newIdx;
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[finishedIdx]?.sender === 'ai') {
                    messages[finishedIdx] = { ...messages[finishedIdx], text: finalText };
                  }
                  if (messages.length <= newIdx) {
                    messages.push({ text: '', sender: 'ai' });
                  }
                  updated.messages = messages;
                  const others = prev.filter((c) => c.id !== activeChatId);
                  return [updated, ...others];
                });
              } else if (textChanged) {
                const idx = aiMessageIndexRef.current;
                const currentText = aiMessageTextRef.current;
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: currentText };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== activeChatId);
                  return [updated, ...others];
                });
              }
            }
          }
        }

        // Поток завершился без [DONE]
        aiMessageTextRef.current = aiMessageTextRef.current.trimEnd();
        setChats((prev) => {
          const chatIndex = prev.findIndex((c) => c.id === activeChatId);
          if (chatIndex === -1) return prev;
          const updated = { ...prev[chatIndex] };
          const messages = [...updated.messages];
          const idx = aiMessageIndexRef.current;
          if (messages[idx]?.sender === 'ai') {
            messages[idx] = { ...messages[idx], text: aiMessageTextRef.current };
            updated.messages = messages;
          }
          const others = prev.filter((c) => c.id !== activeChatId);
          return [updated, ...others];
        });
        fetchAndUpdateTitle(activeChatId);
      } catch (error) {
        if (error.name === 'AbortError') {
          console.log('Stream aborted');
          setChats((prev) => {
            const chatIndex = prev.findIndex((c) => c.id === activeChatId);
            if (chatIndex === -1) return prev;
            const updated = { ...prev[chatIndex] };
            const messages = [...updated.messages];
            const idx = aiMessageIndexRef.current;
            if (messages[idx]?.sender === 'ai') {
              messages[idx] = { ...messages[idx], text: (aiMessageTextRef.current || '').trimEnd() + ' [остановлено]' };
              updated.messages = messages;
            }
            const others = prev.filter((c) => c.id !== activeChatId);
            return [updated, ...others];
          });
        } else {
          console.error('Failed to send message:', error);
          setChats((prev) => {
            const chatIndex = prev.findIndex((c) => c.id === activeChatId);
            if (chatIndex === -1) return prev;
            const updated = { ...prev[chatIndex] };
            const messages = [...updated.messages];
            const idx = aiMessageIndexRef.current;
            if (messages[idx]?.sender === 'ai') {
              messages[idx] = { text: 'Произошла ошибка. Попробуйте еще раз.', sender: 'ai' };
              updated.messages = messages;
            }
            const others = prev.filter((c) => c.id !== activeChatId);
            return [updated, ...others];
          });
        }
      } finally {
        setIsLoading(false);
        abortControllerRef.current = null;
      }
    },
    [activeChatId, fetchAndUpdateTitle],
  );

  const handleStopGeneration = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  }, []);

  const handleNewChat = useCallback(async () => {
    const newId = generateUUID();
    const newChat = { id: newId, title: 'Новый чат', messages: [{ ...DEFAULT_MESSAGE }] };
    setChats((prev) => [newChat, ...prev]);
    setActiveChatId(newId);
    localStorage.setItem(STORAGE_KEY_ACTIVE_ID, newId);
    // (attachment panel stays as-is on new chat)
  }, []);

  const handleDeleteChat = useCallback(
    async (id) => {
      if (chats.length <= 1) return;
      try {
        await fetch(`/api/chat/chat?conversationId=${encodeURIComponent(id)}`, { method: 'DELETE' });
      } catch (err) {
        console.error('Ошибка удаления чата:', err);
      }
      setChats((prev) => prev.filter((chat) => chat.id !== id));
      if (activeChatId === id) {
        const remaining = chats.filter((chat) => chat.id !== id);
        const newActiveId = remaining[0]?.id || null;
        setActiveChatId(newActiveId);
        if (newActiveId) localStorage.setItem(STORAGE_KEY_ACTIVE_ID, newActiveId);
      }
    },
    [chats, activeChatId],
  );

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      setActiveChatId(id);
    },
    [activeChatId],
  );

  // Quick file upload from message input area
  const handleAttachFile = useCallback(
    async (file) => {
      if (!activeChatId || !file) return;
      const formData = new FormData();
      formData.append('file', file);
      try {
        await fetch(`/api/chat/${activeChatId}/attachments`, {
          method: 'POST',
          body: formData,
        });
        // Open attachment panel to show the uploaded file
        setAttachPanelOpen(true);
      } catch (err) {
        console.error('Upload error:', err);
        alert('Ошибка загрузки файла');
      }
    },
    [activeChatId],
  );

  return (
    <div className="chat-app-container">
      {/* ── Left sidebar: chat list only ── */}
      <ChatList
        chats={chats}
        activeChatId={activeChatId}
        onSelectChat={handleSelectChat}
        onNewChat={handleNewChat}
        onDeleteChat={handleDeleteChat}
        onRenameChat={renameChat}
      />

      {/* ── Center: chat window ── */}
      <div className="chat-window">
        {/* Шапка активного чата */}
        {activeChat && (
          <div className="chat-header">
            <div className="chat-header-title">
              {editingTitle ? (
                <input
                  className="chat-header-edit"
                  value={titleDraft}
                  autoFocus
                  onChange={(e) => setTitleDraft(e.target.value)}
                  onBlur={() => {
                    if (titleDraft.trim()) renameChat(activeChat.id, titleDraft.trim());
                    setEditingTitle(false);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') e.target.blur();
                    if (e.key === 'Escape') {
                      setEditingTitle(false);
                    }
                  }}
                />
              ) : (
                <h3
                  title="Нажмите чтобы переименовать"
                  onClick={() => {
                    setTitleDraft(activeChat.title);
                    setEditingTitle(true);
                  }}
                >
                  ️{activeChat.title}
                </h3>
              )}
              <div className="chat-meta">
                {activeChat.createdAt ? `Создан: ${new Date(activeChat.createdAt).toLocaleString()}` : 'Новый чат'}
              </div>
            </div>
            {/* Attachment toggle button in header */}
            <button
              className={`chat-header-attachments-btn ${attachPanelOpen ? 'chat-header-attachments-btn--active' : ''}`}
              onClick={() => setAttachPanelOpen((v) => !v)}
              title="Вложения"
            >
              <IconPaperclip size={15} />
              {attachCount > 0 && <span className="attach-badge">{attachCount}</span>}
            </button>
            <button className="chat-header-delete" onClick={() => handleDeleteChat(activeChat.id)}>
              Удалить
            </button>
          </div>
        )}

        {loadingMessages ? (
          <div className="loading-messages">Загрузка сообщений...</div>
        ) : (
          <MessageList messages={activeMessages} />
        )}

        {/* Message input with attach button */}
        <div className="message-input-row">
          <button
            className="message-input-attach"
            title="Прикрепить файл"
            onClick={() => attachFileRef.current?.click()}
          >
            <IconPaperclip size={18} />
          </button>
          <input
            ref={attachFileRef}
            type="file"
            style={{ display: 'none' }}
            accept="text/*,.md,.json,.yaml,.yml,.xml,.csv,.log,.sql,.java,.js,.jsx,.ts,.tsx,.py,.go,.rs,.html,.css"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleAttachFile(file);
              e.target.value = '';
            }}
          />
          <MessageInput onSend={handleSendMessage} onStop={handleStopGeneration} disabled={isLoading} />
        </div>
      </div>

      {/* ── Right panel: attachments ── */}
      {attachPanelOpen && (
        <div className="chat-attachment-panel">
          <div className="chat-attachment-panel__header">
            <span>📎 Вложения</span>
            <button className="chat-attachment-panel__close" onClick={() => setAttachPanelOpen(false)} title="Закрыть">
              ✕
            </button>
          </div>
          <div className="chat-attachment-panel__body">
            {activeChatId ? (
              <AttachmentPanel
                key={activeChatId}
                ownerType="chat"
                ownerId={activeChatId}
                onCountChange={setAttachCount}
              />
            ) : (
              <p className="chat-attachment-panel__empty">Выберите чат</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default ChatWindow;
