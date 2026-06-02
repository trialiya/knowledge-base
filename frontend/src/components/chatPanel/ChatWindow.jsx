import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';

import MessageList from './MessageList';
import MessageInput from './MessageInput';
import ChatList from './ChatList';
import AttachmentPanel from '../common/AttachmentPanel';
import { IconPaperclip, IconTrash } from '../knowledgeBasePanel/icons';
import './chatWindow.css';
import '../common/attachmentPanel.css';
import CreateJiraChatModal from './CreateJiraChatModal';
import './createJiraChatModal.css';
import JiraAttachmentPanel from './JiraAttachmentPanel';
import './jiraAttachmentPanel.css';
import ErrorModal from '../common/ErrorModal';

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

const ChatWindow = ({ onNavigateToDoc, isActive = true, activeChatId: propActiveChatId = null, onSelectChat }) => {
  const [chats, setChats] = useState([]);
  // Внутреннее зеркало активного чата. Источник правды — проп propActiveChatId
  // (его держит useAppNavigation в App). Локальные выборы поднимаются наверх
  // через onSelectChat и возвращаются сюда уже как проп.
  const [activeChatId, setActiveChatId] = useState(
    propActiveChatId || localStorage.getItem(STORAGE_KEY_ACTIVE_ID) || null,
  );

  // Поднять выбор чата наверх (в навигацию). Локальный стейт обновится, когда
  // App вернёт новый propActiveChatId — но мы также обновляем его сразу, чтобы
  // не зависеть от round-trip и сохранить мгновенную реакцию UI.
  const selectChat = useCallback(
    (id) => {
      setActiveChatId(id);
      if (id) localStorage.setItem(STORAGE_KEY_ACTIVE_ID, id);
      if (onSelectChat) onSelectChat(id);
    },
    [onSelectChat],
  );
  const [isLoading, setIsLoading] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [attachPanelOpen, setAttachPanelOpen] = useState(false);
  const [attachCount, setAttachCount] = useState(0);
  const [jiraModalOpen, setJiraModalOpen] = useState(false);
  // Модалка ошибки загрузки чата: null | { notFound: bool, status }
  const [chatErrorModal, setChatErrorModal] = useState(null);
  const aiMessageTextRef = useRef('');
  const aiMessageIndexRef = useRef(-1);
  const abortControllerRef = useRef(null);
  // Tool calls accumulated for the current AI message segment
  const toolCallsRef = useRef([]);
  const attachFileRef = useRef(null);
  // Ref to hold activeChatId at mount time so the initial fetch effect
  // doesn't need it in its dependency array (we only want this to run once).
  const initialActiveChatIdRef = useRef(activeChatId);
  // Mirror of `chats` so callbacks can read the latest value synchronously
  // without listing `chats` in their dependency arrays (which would recreate
  // them on every streaming chunk).
  const chatsRef = useRef(chats);
  // Guards the one-time chat-list fetch against StrictMode's double-invoke.
  const didFetchChatsRef = useRef(false);
  useEffect(() => {
    chatsRef.current = chats;
  }, [chats]);

  // Источник правды — проп из навигации. Когда он меняется (клик по вкладке,
  // popstate, восстановление из URL), подхватываем активный чат.
  useEffect(() => {
    if (propActiveChatId && propActiveChatId !== activeChatId) {
      setActiveChatId(propActiveChatId);
      localStorage.setItem(STORAGE_KEY_ACTIVE_ID, propActiveChatId);
    }
  }, [propActiveChatId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Загрузка списка чатов
  useEffect(() => {
    if (didFetchChatsRef.current) return;
    didFetchChatsRef.current = true;
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

        const currentId = initialActiveChatIdRef.current;
        const existsInList = chatList.find((c) => c.id === currentId);

        if (currentId && !existsInList) {
          // ID из URL/localStorage не найден в списке — добавляем заглушку,
          // loadMessages попробует загрузить и пометит как ошибку.
          // Автоматически НЕ переключаемся — пусть пользователь видит ошибку.
          const placeholder = { id: currentId, title: '...', messages: null, createdAt: null };
          setChats([placeholder, ...chatList]);
        } else {
          setChats(chatList);
          if (!currentId) {
            // URL был пустой — открываем первый чат
            const firstId = chatList[0]?.id;
            if (firstId) {
              selectChat(firstId);
            }
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

  // Ref для защиты от повторных попыток по chatId, которых нет в списке chats
  const failedChatIdsRef = useRef(new Set());

  // Загрузка сообщений
  const loadMessages = useCallback(async (chatId) => {
    setLoadingMessages(true);
    try {
      const res = await fetch(`/api/chat/chat?conversationId=${encodeURIComponent(chatId)}`);
      if (!res.ok) {
        // Любая ошибка (404, 500, …) — помечаем чат флагом, чтобы остановить повторные запросы
        const isNotFound = res.status === 404;
        failedChatIdsRef.current.add(chatId);
        setChats((prev) =>
          prev.map((chat) =>
            chat.id === chatId ? { ...chat, messages: [], notFound: isNotFound, loadError: res.status } : chat,
          ),
        );
        setChatErrorModal({ notFound: isNotFound, status: res.status });
        return;
      }
      const data = await res.json();
      const msgs =
        data.messages?.map((msg) => ({
          text: msg.content,
          sender: msg.type?.toLowerCase?.() === 'user' ? 'user' : 'ai',
        })) || [];
      const finalMessages = msgs.length > 0 ? msgs : [{ ...DEFAULT_MESSAGE }];
      failedChatIdsRef.current.delete(chatId);
      setChats((prev) =>
        prev.map((chat) =>
          chat.id === chatId ? { ...chat, messages: finalMessages, notFound: false, loadError: null } : chat,
        ),
      );
    } catch (err) {
      // Сетевая ошибка или иное исключение
      console.error('Ошибка загрузки сообщений:', err);
      failedChatIdsRef.current.add(chatId);
      setChats((prev) =>
        prev.map((chat) => (chat.id === chatId ? { ...chat, messages: [], loadError: 'network' } : chat)),
      );
      setChatErrorModal({ notFound: false, status: 'network' });
    } finally {
      setLoadingMessages(false);
    }
  }, []);

  useEffect(() => {
    if (activeChatId) {
      const chat = chats.find((c) => c.id === activeChatId);
      // Не загружаем если: сообщения уже есть, чат помечен как ошибочный,
      // или chatId уже в ref (защита когда чат ещё не появился в списке)
      const alreadyFailed = failedChatIdsRef.current.has(activeChatId);
      if (!chat?.messages && !chat?.notFound && !chat?.loadError && !alreadyFailed) {
        loadMessages(activeChatId);
      }
      // Сохраняем в localStorage только реально существующий чат
      if (!chat?.notFound && !chat?.loadError && !alreadyFailed) {
        localStorage.setItem(STORAGE_KEY_ACTIVE_ID, activeChatId);
      }
    }
  }, [activeChatId, chats, loadMessages]);

  // (Запись URL вынесена в useAppNavigation — ChatWindow историю не трогает.)

  // Fetch attachment count independently so the badge stays accurate
  // even when the attachment panel is closed.
  useEffect(() => {
    if (!activeChatId) return;
    setAttachCount(0);
    fetch(`/api/chat/${encodeURIComponent(activeChatId)}/attachments/count`)
      .then((r) => (r.ok ? r.json() : 0))
      .then((count) => setAttachCount(typeof count === 'number' ? count : 0))
      .catch(() => setAttachCount(0));
  }, [activeChatId]);

  const activeMessages = useMemo(() => chats.find((c) => c.id === activeChatId)?.messages || [], [chats, activeChatId]);
  const activeChat = useMemo(() => chats.find((c) => c.id === activeChatId) || null, [chats, activeChatId]);

  // Чат считается пустым ТОЛЬКО когда сообщения уже загружены (messages !== null)
  // и среди них нет ни одного реального (с полем sender). Пока messages === null
  // (идёт загрузка старого чата), блок не показываем — иначе он мелькает.
  const isChatEmpty = useMemo(() => {
    const msgs = activeChat?.messages;
    if (!Array.isArray(msgs)) return false; // ещё не загружено
    return !msgs.some((m) => m && m.sender);
  }, [activeChat]);

  // Отправка сообщения
  const handleSendMessage = useCallback(
    async (text) => {
      if (!activeChatId) return;

      // Если активный чат недоступен (не найден / ошибка загрузки) —
      // не отправляем запрос, а показываем модалку.
      const chatForSend = chatsRef.current.find((c) => c.id === activeChatId);
      if (chatForSend?.notFound || chatForSend?.loadError) {
        setChatErrorModal({
          notFound: !!chatForSend.notFound,
          status: chatForSend.loadError,
        });
        return;
      }

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
      toolCallsRef.current = [];

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
              let toolCallChanged = false;
              try {
                const parsed = JSON.parse(jsonString);
                const reason = (parsed.finishReason || '').trim();

                // ── Tool call events ──
                if (parsed.toolCall) {
                  const tc = parsed.toolCall;
                  const argsKey = JSON.stringify(tc.arguments || {});
                  const existingIdx = toolCallsRef.current.findIndex(
                    (t) => t.name === tc.name && JSON.stringify(t.arguments || {}) === argsKey,
                  );
                  if (existingIdx >= 0) {
                    toolCallsRef.current = toolCallsRef.current.map((t, i) =>
                      i === existingIdx
                        ? { ...t, status: tc.status, error: tc.error, resultGist: tc.resultGist ?? t.resultGist }
                        : t,
                    );
                  } else {
                    toolCallsRef.current = [
                      ...toolCallsRef.current,
                      {
                        name: tc.name,
                        arguments: tc.arguments,
                        status: tc.status,
                        error: tc.error,
                        resultGist: tc.resultGist,
                      },
                    ];
                  }
                  toolCallChanged = true;
                }

                // ── toolCalls summary (final, from onComplete) ──
                if (parsed.toolCalls && Array.isArray(parsed.toolCalls)) {
                  // Merge any missing tool calls from the summary
                  for (const tc of parsed.toolCalls) {
                    const argsKey = JSON.stringify(tc?.arguments || {});
                    const exists = toolCallsRef.current.some(
                      (t) => t.name === tc.name && JSON.stringify(t.arguments || {}) === argsKey,
                    );
                    if (!exists) {
                      toolCallsRef.current = [
                        ...toolCallsRef.current,
                        {
                          name: tc.name,
                          arguments: tc.arguments,
                          status: tc.status,
                          error: tc.error,
                          resultGist: tc.resultGist,
                        },
                      ];
                    } else {
                      toolCallsRef.current = toolCallsRef.current.map((t) =>
                        t.name === tc.name && JSON.stringify(t.arguments || {}) === argsKey
                          ? { ...t, status: tc.status, error: tc.error, resultGist: tc.resultGist ?? t.resultGist }
                          : t,
                      );
                    }
                  }
                  toolCallChanged = true;
                }

                // Текстовый контент — добавляем к накопленному.
                // Пропускаем пустые строки, а также ведущие переносы строк
                // в самом начале ответа (модель иногда шлёт "\n\n" первым чанком).
                if (parsed.message) {
                  const isFirstChunk = aiMessageTextRef.current === '';
                  const text = isFirstChunk ? parsed.message.replace(/^\n+/, '') : parsed.message;
                  if (text) {
                    aiMessageTextRef.current += text;
                    textChanged = true;
                  }
                }

                // DONE — бэкенд шлёт из onComplete(); финализируем весь ответ.
                if (reason === 'DONE') {
                  isDone = true;
                }
                // TOOL_CALLS — модель вызвала инструмент, дальше будет новый
                // текстовый сегмент. Создаём новое AI-сообщение, но только если
                // в текущем уже накоплен непустой текст.
                else if (reason === 'TOOL_CALLS' && aiMessageTextRef.current.trim() !== '') {
                  shouldFinishSegment = true;
                }
                // STOP — текстовый ответ закончен. НЕ создаём новый сегмент —
                // после STOP может прийти ещё DONE или пустые чанки.
                // Просто игнорируем.
              } catch {
                /* ignore parse errors */
              }

              if (isDone) {
                aiMessageTextRef.current = aiMessageTextRef.current.trimEnd();
                const finalText = aiMessageTextRef.current;
                const finalToolCalls = [...toolCallsRef.current];
                const idx = aiMessageIndexRef.current;
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: finalText, toolCalls: finalToolCalls };
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
                const segmentToolCalls = [...toolCallsRef.current];
                aiMessageTextRef.current = '';
                toolCallsRef.current = [];
                const finishedIdx = aiMessageIndexRef.current;
                const newIdx = finishedIdx + 1;
                aiMessageIndexRef.current = newIdx;
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[finishedIdx]?.sender === 'ai') {
                    messages[finishedIdx] = { ...messages[finishedIdx], text: finalText, toolCalls: segmentToolCalls };
                  }
                  if (messages.length <= newIdx) {
                    messages.push({ text: '', sender: 'ai' });
                  }
                  updated.messages = messages;
                  const others = prev.filter((c) => c.id !== activeChatId);
                  return [updated, ...others];
                });
              } else if (textChanged || toolCallChanged) {
                const idx = aiMessageIndexRef.current;
                const currentText = aiMessageTextRef.current;
                const currentToolCalls = [...toolCallsRef.current];
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === activeChatId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: currentText, toolCalls: currentToolCalls };
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
    selectChat(newId);
    // (attachment panel stays as-is on new chat)
  }, [selectChat]);

  const handleCreateJiraChat = useCallback(
    async (request) => {
      const res = await fetch('/api/chat/jira', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || 'Ошибка создания JIRA чата');
      }
      const chat = await res.json();
      const newChat = {
        id: chat.conversationId,
        title: chat.topic || 'JIRA чат',
        messages: null,
        createdAt: chat.createdAt || null,
        jiraUrl: request.jiraUrl,
      };
      setChats((prev) => [newChat, ...prev]);
      selectChat(newChat.id);
      setAttachPanelOpen(true); // Show attachments with fetched content
    },
    [selectChat],
  );

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
        selectChat(newActiveId);
      }
    },
    [chats, activeChatId, selectChat],
  );

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      selectChat(id);
      setAttachCount(0); // reset until new panel loads count for new chat
    },
    [activeChatId, selectChat],
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
        setAttachCount((n) => n + 1);
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
        onNewJiraChat={() => setJiraModalOpen(true)}
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
              <IconTrash />
            </button>
          </div>
        )}

        {loadingMessages ? (
          <div className="loading-messages">Загрузка сообщений...</div>
        ) : activeChat?.notFound || activeChat?.loadError ? (
          <div className="loading-messages" style={{ flexDirection: 'column', gap: '0.5rem' }}>
            <span style={{ fontSize: '2rem' }}>{activeChat?.notFound ? '🔍' : '⚠️'}</span>
            <span>{activeChat?.notFound ? 'Чат не найден' : 'Не удалось загрузить чат'}</span>
            <span style={{ fontSize: '0.8rem', opacity: 0.7 }}>
              {activeChat?.notFound
                ? 'Возможно, он был удалён или ссылка устарела'
                : `Ошибка сервера (${activeChat?.loadError}). Попробуйте позже.`}
            </span>
          </div>
        ) : (
          <MessageList messages={activeMessages} onNavigateToDoc={onNavigateToDoc} />
        )}

        {/* Message input with inline attach */}
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
        {activeChat?.notFound || activeChat?.loadError ? (
          <div className="message-input-wrapper message-input-wrapper--disabled">
            <span className="message-input-disabled-note">
              {activeChat?.notFound
                ? 'Этот чат недоступен — выберите другой или создайте новый.'
                : 'Чат не загружен. Выберите другой чат или попробуйте позже.'}
            </span>
          </div>
        ) : (
          <MessageInput
            onSend={handleSendMessage}
            onStop={handleStopGeneration}
            disabled={isLoading}
            onAttach={() => attachFileRef.current?.click()}
            isEmpty={isChatEmpty && !loadingMessages}
          />
        )}
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
              activeChat?.jiraUrl ? (
                // JIRA-чат: компактные карточки + кнопка обновления
                <JiraAttachmentPanel
                  key={activeChatId}
                  conversationId={activeChatId}
                  jiraUrl={activeChat.jiraUrl}
                  onCountChange={setAttachCount}
                />
              ) : (
                // Обычный чат: стандартная таблица с загрузкой файлов
                <AttachmentPanel
                  key={activeChatId}
                  ownerType="chat"
                  ownerId={activeChatId}
                  onCountChange={setAttachCount}
                />
              )
            ) : (
              <p className="chat-attachment-panel__empty">Выберите чат</p>
            )}
          </div>
        </div>
      )}
      <CreateJiraChatModal
        open={jiraModalOpen}
        onClose={() => setJiraModalOpen(false)}
        onCreate={handleCreateJiraChat}
      />
      <ErrorModal
        open={!!chatErrorModal}
        icon={chatErrorModal?.notFound ? '🔍' : '⚠️'}
        title={chatErrorModal?.notFound ? 'Чат не найден' : 'Не удалось загрузить чат'}
        message={
          chatErrorModal?.notFound
            ? 'Возможно, он был удалён или ссылка устарела. Выберите другой чат или создайте новый.'
            : `Ошибка при загрузке чата${
                chatErrorModal && chatErrorModal.status !== 'network' ? ` (${chatErrorModal.status})` : ''
              }. Попробуйте позже.`
        }
        onClose={() => setChatErrorModal(null)}
      />
    </div>
  );
};

export default ChatWindow;
