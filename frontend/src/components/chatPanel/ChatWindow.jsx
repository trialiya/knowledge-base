import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import attachmentApi from '../../api/attachmentApi';
import { STORAGE_KEY_ACTIVE_CHAT, STORAGE_KEY_LAST_MODEL, DRAFT_CHAT_ID } from '../../constants/storage';
import { loadDrafts, saveDrafts, getDraft, setDraft } from './chatDrafts';
import { nextMessageId } from './messageId';
import useModelConfig from './useModelConfig';
import useIntegrationsConfig from './useIntegrationsConfig';
import useChatMessages from './useChatMessages';
import useChatEventStream from './useChatEventStream';
import useInChatSearch from './useInChatSearch';

import MessageList from './MessageList';
import MessageInput from './MessageInput';
import ChatList from './ChatList';
import ChatSearchBar from './ChatSearchBar';
import ModelSelector from './ModelSelector';
import AttachmentPanel from '../common/AttachmentPanel';
import { IconPaperclip, IconTrash, IconSearch } from '../../icons';
import './chatWindow.css';
import CreateJiraChatModal from './CreateJiraChatModal';
import JiraAttachmentPanel from './JiraAttachmentPanel';
import ErrorModal from '../common/ErrorModal';
import ConfirmModal from '../common/ConfirmModal';

const generateUUID = () => {
  if (crypto?.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
};

const ChatWindow = ({ onNavigateToDoc, isActive = true, activeChatId: propActiveChatId = null, onSelectChat }) => {
  const { t } = useTranslation('chat');
  const [chats, setChats] = useState([]);
  // Внутреннее зеркало активного чата. Источник правды — проп propActiveChatId
  // (его держит useAppNavigation в App). Локальные выборы поднимаются наверх
  // через onSelectChat и возвращаются сюда уже как проп.
  const [activeChatId, setActiveChatId] = useState(
    propActiveChatId || localStorage.getItem(STORAGE_KEY_ACTIVE_CHAT) || null,
  );

  // Поднять выбор чата наверх (в навигацию). Локальный стейт обновится, когда
  // App вернёт новый propActiveChatId — но мы также обновляем его сразу, чтобы
  // не зависеть от round-trip и сохранить мгновенную реакцию UI.
  const selectChat = useCallback(
    (id) => {
      setActiveChatId(id);
      if (id) localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, id);
      if (onSelectChat) onSelectChat(id);
    },
    [onSelectChat],
  );

  // Создаёт объект черновика. model берём из последней использованной (localStorage),
  // иначе сработает фолбэк на дефолтную модель в selectedModelId/отправке.
  const makeDraft = useCallback(
    () => ({
      id: DRAFT_CHAT_ID,
      title: tRef.current('window.defaultTitle'),
      messages: [],
      model: lastModelRef.current || null,
      draft: true,
    }),
    [],
  );
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [attachPanelOpen, setAttachPanelOpen] = useState(false);
  const [attachCount, setAttachCount] = useState(0);
  const [jiraModalOpen, setJiraModalOpen] = useState(false);
  // Конфиг моделей и интеграций грузятся один раз — вынесено в отдельные хуки.
  const { modelConfig, modelOptions } = useModelConfig();
  const { jiraConfigured, confluenceConfigured } = useIntegrationsConfig();
  // Последняя модель, с которой отправляли сообщение (живёт между перезагрузками).
  const lastModelRef = useRef(localStorage.getItem(STORAGE_KEY_LAST_MODEL) || null);
  // Модалка ошибки загрузки чата: null | { notFound: bool, status }
  const [chatErrorModal, setChatErrorModal] = useState(null);
  // Модалка подтверждения удаления чата: null | { id, title }
  const [chatDeleteConfirm, setChatDeleteConfirm] = useState(null);
  // Уведомление «в чате уже идёт генерация» (ответ сервера 409 на старт прогона).
  const [busyNotice, setBusyNotice] = useState(false);
  // Уведомление «чат удалён в другой вкладке» (событие CHAT_DELETED из потока).
  const [deletedNotice, setDeletedNotice] = useState(false);
  // Уведомление об ошибке загрузки файла (вместо нативного alert).
  const [uploadErrorNotice, setUploadErrorNotice] = useState(false);
  // Уведомление об ошибке удаления чата на сервере: null | { status }.
  const [deleteErrorNotice, setDeleteErrorNotice] = useState(null);
  // Bump → очистить текст в MessageInput («удаление» черновика).
  const [composerResetSignal, setComposerResetSignal] = useState(0);
  // Bump → сфокусировать MessageInput (при активации панели чата).
  const [composerFocusSignal, setComposerFocusSignal] = useState(0);
  // Неотправленные черновики по чатам ({ chatId: text }). Живут в localStorage,
  // чтобы переключение чатов и перезагрузка не теряли набранный текст.
  const draftsRef = useRef(loadDrafts());
  const draftsPersistTimerRef = useRef(null);
  // Отложенная запись черновиков на диск (на каждый keystroke писать не нужно).
  const scheduleDraftsPersist = useCallback(() => {
    clearTimeout(draftsPersistTimerRef.current);
    draftsPersistTimerRef.current = setTimeout(() => saveDrafts(draftsRef.current), 400);
  }, []);
  // Обновить черновик активного чата из поля ввода.
  const handleComposerTextChange = useCallback(
    (id, text) => {
      setDraft(draftsRef.current, id, text);
      scheduleDraftsPersist();
    },
    [scheduleDraftsPersist],
  );
  // Полностью убрать черновик чата (после отправки / удаления) и сохранить сразу.
  const clearDraft = useCallback((id) => {
    setDraft(draftsRef.current, id, '');
    saveDrafts(draftsRef.current);
  }, []);
  // Гасим таймер и фиксируем последний черновик. На полную перезагрузку/закрытие
  // вкладки cleanup эффекта не срабатывает — поэтому ещё и beforeunload-flush.
  useEffect(() => {
    const flush = () => {
      clearTimeout(draftsPersistTimerRef.current);
      saveDrafts(draftsRef.current);
    };
    window.addEventListener('beforeunload', flush);
    return () => {
      window.removeEventListener('beforeunload', flush);
      flush();
    };
  }, []);
  // clientMsgId-ы сообщений, отправленных ИЗ ЭТОЙ вкладки. Нужны, чтобы не задвоить
  // свой оптимистично показанный пузырь, получив его же эхом из потока событий.
  const localClientIdsRef = useRef(new Set());
  // id чатов, которые удаляем из ЭТОЙ вкладки — чтобы не показать себе же модалку
  // «удалён в другой вкладке», получив собственное эхо CHAT_DELETED.
  const locallyDeletingRef = useRef(new Set());
  const attachFileRef = useRef(null);
  // Ref to hold activeChatId at mount time so the initial fetch effect
  // doesn't need it in its dependency array (we only want this to run once).
  const initialActiveChatIdRef = useRef(activeChatId);
  // chatId, заданный явно в URL (?chat=...) на момент монтирования. null — когда
  // в URL чата нет (например, просто ?view=chat). Позволяет отличить «осознанную
  // ссылку на чат» от id, подставленного из localStorage (он может быть устаревшим).
  const initialPropChatIdRef = useRef(propActiveChatId);
  // Mirror of `chats` so callbacks can read the latest value synchronously
  // without listing `chats` in their dependency arrays (which would recreate
  // them on every streaming chunk).
  const chatsRef = useRef(chats);
  // Guards the one-time chat-list fetch against StrictMode's double-invoke.
  const didFetchChatsRef = useRef(false);
  // Зеркало t() для использования внутри стрим-колбэков без добавления t в deps
  // (иначе колбэк пересоздавался бы при смене языка во время стриминга).
  const tRef = useRef(t);
  useEffect(() => {
    tRef.current = t;
  }, [t]);
  useEffect(() => {
    chatsRef.current = chats;
  }, [chats]);

  // Источник правды — проп из навигации. Когда он меняется (клик по вкладке,
  // popstate, восстановление из URL), подхватываем активный чат.
  useEffect(() => {
    if (propActiveChatId && propActiveChatId !== activeChatId) {
      setActiveChatId(propActiveChatId);
      localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, propActiveChatId);
    }
  }, [propActiveChatId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Фокус на поле ввода при переключении на панель чата.
  useEffect(() => {
    if (isActive) setComposerFocusSignal((n) => n + 1);
  }, [isActive]);

  // Загрузка списка чатов
  useEffect(() => {
    if (didFetchChatsRef.current) return;
    didFetchChatsRef.current = true;
    const fetchChats = async () => {
      try {
        const data = await chatApi.listChats();
        const chatList = data.map((chat) => ({
          id: chat.conversationId,
          title: chat.topic || tRef.current('window.defaultTitle'),
          messages: null,
          createdAt: chat.createdAt || null,
          model: chat.model || null,
        }));

        const currentId = initialActiveChatIdRef.current;
        // Был ли чат задан явно в URL. Если нет — currentId пришёл из localStorage
        // (память последнего чата) и вполне может оказаться устаревшим/удалённым.
        const fromUrl = !!initialPropChatIdRef.current;
        const existsInList = chatList.some((c) => c.id === currentId);

        if (currentId === DRAFT_CHAT_ID) {
          // Перезагрузка на черновике: бэк о нём ничего не знает и знать не должен.
          // Показываем свежий пустой черновик, не пытаясь его грузить (никакой ошибки).
          setChats([makeDraft(), ...chatList]);
        } else if (currentId && existsInList) {
          // Чат из URL/localStorage реально существует — открываем как есть.
          setChats(chatList);
        } else if (currentId && fromUrl) {
          // Явный ?chat=<id> в URL, которого больше нет (устаревшая ссылка) —
          // показываем «не найдено». Автоматически НЕ переключаемся: пользователь
          // перешёл по конкретной ссылке и должен увидеть, что чат недоступен.
          const placeholder = { id: currentId, title: '...', messages: null, createdAt: null, model: null };
          setChats([placeholder, ...chatList]);
        } else {
          // Чат в URL не задан (его нет вовсе, либо id из localStorage устарел) —
          // ошибку НЕ показываем: открываем первый существующий чат, а если чатов
          // нет — стартуем с пустого черновика «new» (поведение как у «Новый чат»).
          const firstId = chatList[0]?.id;
          if (firstId) {
            setChats(chatList);
            selectChat(firstId);
          } else {
            setChats([makeDraft()]);
            selectChat(DRAFT_CHAT_ID);
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
      await chatApi.renameChat(chatId, newTitle);
      setChats((prev) => prev.map((chat) => (chat.id === chatId ? { ...chat, title: newTitle } : chat)));
    } catch (err) {
      console.error('Ошибка переименования чата:', err);
    }
  }, []);

  // Фоновое обновление темы чата с бэкенда после ответа
  const fetchAndUpdateTitle = useCallback(async (chatId) => {
    try {
      const data = await chatApi.getChatMeta(chatId);
      const newTitle = data.topic;
      setChats((prev) =>
        prev.map((chat) =>
          chat.id === chatId
            ? {
                ...chat,
                ...(newTitle ? { title: newTitle } : {}),
                model: data.model ?? chat.model ?? null,
                // не затираем уже имеющийся createdAt, иначе берём из ответа
                createdAt: chat.createdAt ?? data.createdAt ?? null,
              }
            : chat,
        ),
      );
    } catch (err) {
      console.error('Ошибка обновления темы чата:', err);
    }
  }, []);

  // Загрузка/пагинация сообщений активного чата (+ защита от повторных загрузок и
  // запоминание активного чата) вынесены в useChatMessages.
  const { loadingMessages, loadMessages, loadOlderMessages } = useChatMessages({
    chats,
    chatsRef,
    setChats,
    activeChatId,
    onLoadError: setChatErrorModal,
  });

  const handleLoadOlder = useCallback(() => loadOlderMessages(activeChatId), [activeChatId, loadOlderMessages]);

  // (Запись URL вынесена в useAppNavigation — ChatWindow историю не трогает.)

  // Fetch attachment count independently so the badge stays accurate
  // even when the attachment panel is closed.
  useEffect(() => {
    if (!activeChatId || activeChatId === DRAFT_CHAT_ID) return;
    setAttachCount(0);
    chatApi
      .getAttachmentCount(activeChatId)
      .then((count) => setAttachCount(typeof count === 'number' ? count : 0))
      .catch(() => setAttachCount(0));
  }, [activeChatId]);

  const activeChat = useMemo(() => chats.find((c) => c.id === activeChatId) || null, [chats, activeChatId]);
  const activeMessages = useMemo(() => activeChat?.messages || [], [activeChat]);

  // Идёт генерация в активном чате? Источник правды — runId чата (его ставит старт
  // прогона и снимает терминальное событие). Управляет блокировкой ввода и видом
  // кнопки (отправить ↔ остановить).
  const isStreaming = !!activeChat?.runId;

  // Поиск сообщений внутри активного чата (find-бар, Ctrl+F / кнопка-лупа в шапке).
  // messages передаём из рендера (chatsRef обновляется эффектом и на рендер отстаёт).
  const inChatSearch = useInChatSearch({
    activeChatId,
    chatsRef,
    loadOlderMessages,
    messages: activeChat?.messages,
  });
  const inChatSearchInputRef = useRef(null);
  const canSearchChat =
    !!activeChatId && activeChatId !== DRAFT_CHAT_ID && !activeChat?.notFound && !activeChat?.loadError;

  // Ctrl/Cmd+F открывает (или фокусирует уже открытый) find-бар текущего чата —
  // только пока вкладка «Чат» активна, иначе перехватывали бы поиск в других вкладках.
  useEffect(() => {
    if (!isActive) return undefined;
    const onKeyDown = (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.shiftKey || e.altKey) return;
      // e.code — физическая клавиша: на нелатинских раскладках (например, русской)
      // e.key даёт символ раскладки («а»), и проверка только по key ломает шорткат.
      if (e.key !== 'f' && e.key !== 'F' && e.code !== 'KeyF') return;
      if (!canSearchChat) return;
      e.preventDefault();
      if (inChatSearch.open) {
        inChatSearchInputRef.current?.focus();
        inChatSearchInputRef.current?.select();
      } else {
        inChatSearch.openBar();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isActive, canSearchChat, inChatSearch]);

  // Список для сайдбара: черновик «new» не показываем, пока в нём нет сообщений.
  // Он промоутится в реальный чат (с UUID и draft:false) при отправке первого
  // сообщения — тогда и появляется пунктом в списке. В главном окне черновик при
  // этом остаётся активным (берётся из полного chats), печатать в него можно.
  const visibleChats = useMemo(() => chats.filter((c) => c.id !== DRAFT_CHAT_ID), [chats]);

  // Выбранная в селекторе модель. Если у чата модель не задана или её больше нет
  // в конфиге — показываем дефолтную (чтобы select оставался валидным).
  const selectedModelId = useMemo(() => {
    const def = modelConfig?.defaultModel?.id || '';
    const m = activeChat?.model;
    return m && modelOptions.some((o) => o.id === m) ? m : def;
  }, [activeChat, modelOptions, modelConfig]);

  // Чат считается пустым ТОЛЬКО когда сообщения уже загружены (messages !== null)
  // и среди них нет ни одного реального (с полем sender). Пока messages === null
  // (идёт загрузка старого чата), блок не показываем — иначе он мелькает.
  const isChatEmpty = useMemo(() => {
    const msgs = activeChat?.messages;
    if (!Array.isArray(msgs)) return false; // ещё не загружено
    return !msgs.some((m) => m && m.sender);
  }, [activeChat]);

  // Модель для отправки — всегда явная: выбранная у чата → последняя → дефолтная.
  const resolveModelForSend = useCallback(
    (chat) => {
      const selected = chat?.model;
      if (selected && modelOptions.some((o) => o.id === selected)) return selected;
      if (lastModelRef.current && modelOptions.some((o) => o.id === lastModelRef.current)) return lastModelRef.current;
      return modelConfig?.defaultModel?.id || null;
    },
    [modelOptions, modelConfig],
  );

  // Старт фонового прогона для уже показанного вопроса. Общий код для первой отправки
  // и для «Повторить»: бьёт POST /runs и обрабатывает исход — runId (идёт генерация),
  // 409 (занято) или ошибку (помечаем пузырь error+retryText, чтобы можно было повторить).
  const runConversation = useCallback(async (conversationId, text, clientMsgId, modelForSend) => {
    // Запоминаем как «последнюю» — новый чат стартует именно с неё.
    if (modelForSend) {
      lastModelRef.current = modelForSend;
      try {
        localStorage.setItem(STORAGE_KEY_LAST_MODEL, modelForSend);
      } catch {
        /* ignore quota errors */
      }
    }
    try {
      const res = await chatApi.startRun(conversationId, text, { model: modelForSend, clientMsgId });
      const runId = res?.runId;
      // Помечаем чат активным прогоном → кнопка «остановить», блокировка ввода.
      // (RUN_STARTED из потока проставит то же самое, если опередит.)
      if (runId) {
        setChats((prev) => prev.map((c) => (c.id === conversationId ? { ...c, runId } : c)));
      }
    } catch (error) {
      // Не наша заявка — генерация уже идёт (часто из другой вкладки). Откатываем
      // оптимистичный пузырь (если был) и сообщаем пользователю. Текущий прогон всё
      // равно «прилетит» потоком событий (RUN_STARTED) и покажет «остановить».
      if (error?.status === 409) {
        localClientIdsRef.current.delete(clientMsgId);
        setChats((prev) =>
          prev.map((c) =>
            c.id === conversationId
              ? { ...c, messages: (c.messages || []).filter((m) => m.clientMsgId !== clientMsgId) }
              : c,
          ),
        );
        setBusyNotice(true);
        return;
      }
      // Сетевой сбой / 5xx — POST не стартовал прогон. Показываем пузырь с ошибкой и
      // retryText, чтобы пользователь мог переотправить тот же вопрос (см. Message.jsx).
      console.error('Failed to start run:', error);
      setChats((prev) =>
        prev.map((c) =>
          c.id === conversationId
            ? {
                ...c,
                runId: null,
                messages: [
                  ...(c.messages || []),
                  {
                    mid: nextMessageId(),
                    text: tRef.current('window.genericError'),
                    sender: 'ai',
                    error: true,
                    retryText: text,
                  },
                ],
              }
            : c,
        ),
      );
    }
  }, []);

  // Отправка сообщения. Больше НЕ стримит ответ из этого запроса: лишь запускает
  // фоновый прогон (POST /runs) и оптимистично показывает свой вопрос. Сам ответ
  // (и эхо вопроса для других вкладок) приедет потоком событий — см. эффект ниже.
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

      // Черновик: настоящий conversationId (UUID) рождается именно сейчас.
      // Для обычного чата conversationId === activeChatId.
      const isDraft = activeChatId === DRAFT_CHAT_ID;
      const conversationId = isDraft ? generateUUID() : activeChatId;
      // clientMsgId — чтобы не задвоить свой пузырь, получив его эхом из /events.
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      const modelForSend = resolveModelForSend(chatForSend);

      // Оптимистично: промоутим черновик и показываем пузырь пользователя.
      // AI-пузырь не добавляем — его создаст событие RUN_STARTED.
      setChats((prev) => {
        const found = prev.find((c) => c.id === activeChatId);
        if (!found) return prev;
        const newMessages = [
          ...(found.messages || []),
          { mid: nextMessageId(), text, sender: 'user', clientMsgId, timestamp: new Date().toISOString() },
        ];
        const updatedChat = {
          ...found,
          id: conversationId,
          draft: false,
          model: modelForSend ?? found.model ?? null,
          messages: newMessages,
        };
        const otherChats = prev.filter((c) => c.id !== activeChatId);
        return [updatedChat, ...otherChats];
      });

      // Поднимаем реальный id в URL/навигацию: '/new' → '/<uuid>'.
      if (isDraft) {
        selectChat(conversationId);
      }
      // Сообщение ушло — черновик этого чата больше не нужен.
      clearDraft(activeChatId);

      await runConversation(conversationId, text, clientMsgId, modelForSend);
    },
    [activeChatId, selectChat, resolveModelForSend, runConversation, clearDraft],
  );

  // Переотправить вопрос после ошибки: убираем ошибочный AI-пузырь и заново запускаем
  // прогон по тому же тексту. Пузырь пользователя уже на месте — новый не добавляем,
  // эхо USER_MESSAGE гасится по clientMsgId. Чистый случай (без дублей на бэке) —
  // сбой самого POST /runs: прогон не стартовал и вопрос ещё не сохранён. Для
  // асинхронной RUN_ERROR вопрос уже в памяти чата, поэтому повтор создаёт новый ход.
  const handleRetryMessage = useCallback(
    (index) => {
      const chat = chatsRef.current.find((c) => c.id === activeChatId);
      if (!chat || chat.runId) return; // во время генерации повтор недоступен
      const msgs = chat.messages || [];
      const target = msgs[index];
      if (!target || target.sender !== 'ai' || !target.error) return;
      // Текст для повтора: явный retryText или ближайший пузырь пользователя выше.
      let text = target.retryText || null;
      if (!text) {
        for (let i = index - 1; i >= 0; i--) {
          if (msgs[i].sender === 'user') {
            text = msgs[i].text;
            break;
          }
        }
      }
      if (!text || !text.trim()) return;
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      // Снимаем ошибочный AI-пузырь, чтобы не копить ошибки.
      setChats((prev) =>
        prev.map((c) =>
          c.id === activeChatId ? { ...c, messages: (c.messages || []).filter((_, i) => i !== index) } : c,
        ),
      );
      runConversation(activeChatId, text, clientMsgId, resolveModelForSend(chat));
    },
    [activeChatId, resolveModelForSend, runConversation],
  );

  const handleStopGeneration = useCallback(() => {
    const chat = chatsRef.current.find((c) => c.id === activeChatId);
    if (chat?.runId) {
      // Явный сигнал на бэк. Бубл обновит событие RUN_STOPPED (во всех вкладках).
      chatApi.stopRun(chat.id, chat.runId);
    }
  }, [activeChatId]);

  // Поток событий активного чата: стриминг ответа + синхронизация между вкладками.
  // Подключаемся ТОЛЬКО когда история уже загружена (messages — массив), чтобы
  // события легли поверх неё, а не были затёрты последующей загрузкой из БД. При
  // обрыве/перезагрузке поток сам переподключается и дозагружает пропущенное, так
  // что ответ продолжает «течь» после reload и догоняется поздно открытой вкладкой.
  // Чат удалён извне (из другой вкладки/сессии). Поток событий открыт только для активного
  // чата, поэтому событие приходит лишь когда удалили именно открытый чат.
  const handleRemoteChatDeleted = useCallback(
    (id) => {
      if (locallyDeletingRef.current.delete(id)) {
        // Это эхо нашего же удаления — UI уже обновлён в confirmDeleteChat, молчим.
        return;
      }
      setChats((prev) => prev.filter((c) => c.id !== id));
      setDeletedNotice(true);
      const remaining = chatsRef.current.filter((c) => c.id !== id);
      selectChat(remaining[0]?.id || null);
    },
    [selectChat],
  );

  const activeMessagesReady = Array.isArray(activeChat?.messages);
  useChatEventStream({
    activeChatId,
    activeMessagesReady,
    chatsRef,
    localClientIdsRef,
    tRef,
    setChats,
    onChatDeleted: handleRemoteChatDeleted,
    onRunSettled: fetchAndUpdateTitle,
    reloadMessages: loadMessages,
  });

  const handleNewChat = useCallback(() => {
    // Создаём черновик: реального id ещё нет (в URL будет 'new'), на бэк ничего
    // не пишем. UUID и запись в БД появятся при отправке первого сообщения.
    // Держим максимум один черновик в списке.
    setChats((prev) => [makeDraft(), ...prev.filter((c) => c.id !== DRAFT_CHAT_ID)]);
    selectChat(DRAFT_CHAT_ID);
    // (attachment panel stays as-is on new chat)
  }, [selectChat, makeDraft]);

  const handleCreateJiraChat = useCallback(
    async (request) => {
      let chat;
      try {
        chat = await chatApi.createJiraChat(request);
      } catch (err) {
        throw new Error(err.body || tRef.current('window.jiraCreateError'));
      }
      const newChat = {
        id: chat.conversationId,
        title: chat.topic || tRef.current('window.jiraTitle'),
        messages: null,
        createdAt: chat.createdAt || null,
        model: chat.model || null,
        jiraUrl: request.jiraUrl,
      };
      setChats((prev) => [newChat, ...prev]);
      selectChat(newChat.id);
      setAttachPanelOpen(true); // Show attachments with fetched content
    },
    [selectChat],
  );

  const handleDeleteChat = useCallback(
    (id) => {
      if (id === DRAFT_CHAT_ID) {
        // У черновика нет сущности на бэке — «удаление» лишь очищает поле ввода.
        // Сам черновик и выбранная модель остаются.
        clearDraft(DRAFT_CHAT_ID);
        setComposerResetSignal((n) => n + 1);
        return;
      }
      if (chats.length <= 1) return;
      const chat = chats.find((c) => c.id === id);
      setChatDeleteConfirm({ id, title: chat?.title ?? '' });
    },
    [chats, clearDraft],
  );

  // Реальное удаление — после подтверждения в модалке. Помечаем как «наше»
  // удаление ДО запроса — эхо CHAT_DELETED по потоку не покажет нам модалку
  // «удалён в другой вкладке» — но снимаем метку обратно, если сервер отказал:
  // иначе будущее реальное удаление этого чата (кем-то другим) молча
  // проигнорируется, как будто это снова наше же эхо.
  const confirmDeleteChat = useCallback(async () => {
    const target = chatDeleteConfirm;
    setChatDeleteConfirm(null);
    if (!target) return;
    const { id } = target;
    locallyDeletingRef.current.add(id);
    try {
      const res = await chatApi.deleteChat(id);
      if (!res.ok) {
        locallyDeletingRef.current.delete(id);
        setDeleteErrorNotice({ status: res.status });
        return;
      }
    } catch {
      locallyDeletingRef.current.delete(id);
      setDeleteErrorNotice({ status: 'network' });
      return;
    }
    clearDraft(id); // черновик удалённого чата больше не нужен
    setChats((prev) => prev.filter((chat) => chat.id !== id));
    if (activeChatId === id) {
      const remaining = chatsRef.current.filter((chat) => chat.id !== id);
      const newActiveId = remaining[0]?.id || null;
      selectChat(newActiveId);
    }
  }, [chatDeleteConfirm, activeChatId, selectChat, clearDraft]);

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      // Раньше здесь стояла блокировка переключения при наборе >3 символов — из-за
      // неё выбор чата в списке «переставал работать». Теперь черновик каждого чата
      // сохраняется отдельно (см. draftsRef), поэтому переключаться можно свободно.
      saveDrafts(draftsRef.current); // зафиксировать текущий черновик до ухода
      selectChat(id);
      setAttachCount(0); // reset until new panel loads count for new chat
    },
    [activeChatId, selectChat],
  );

  // Выбор результата поиска по чатам (сайдбар): открываем чат и, если совпадение
  // было по сообщениям, сразу запускаем в нём find-бар с тем же запросом — он
  // по умолчанию садится на самое свежее совпадение, то же, что дало сниппет.
  const handleChatSearchSelect = useCallback(
    (result, query) => {
      handleSelectChat(result.conversationId);
      if (result.messageMatchCount > 0 && query) {
        inChatSearch.openWithQuery(query);
      }
    },
    [handleSelectChat, inChatSearch],
  );

  // Смена модели активного чата. Храним выбранный id как есть (модель всегда явная).
  // Для черновика на бэке чата ещё нет — PUT откладываем, модель уедет с первым сообщением.
  const handleModelChange = useCallback(
    async (newId) => {
      if (!activeChatId) return;
      // Оптимистично обновляем локально — UI реагирует мгновенно.
      setChats((prev) => prev.map((c) => (c.id === activeChatId ? { ...c, model: newId } : c)));
      if (activeChatId === DRAFT_CHAT_ID) return;
      try {
        await chatApi.updateModel(activeChatId, newId);
      } catch (err) {
        console.error('Ошибка смены модели чата:', err);
      }
    },
    [activeChatId],
  );

  // Quick file upload from message input area
  const handleAttachFile = useCallback(
    async (file) => {
      if (!activeChatId || !file) return;
      try {
        await attachmentApi.upload('chat', activeChatId, file);
        setAttachCount((n) => n + 1);
        // Open attachment panel to show the uploaded file
        setAttachPanelOpen(true);
      } catch (err) {
        console.error('Upload error:', err);
        setUploadErrorNotice(true);
      }
    },
    [activeChatId],
  );

  // Суффикс с кодом ошибки для сообщения модалки (если это не сетевой сбой).
  const errorModalSuffix = chatErrorModal && chatErrorModal.status !== 'network' ? ` (${chatErrorModal.status})` : '';

  return (
    <div className="chat-app-container">
      {/* ── Left sidebar: chat list only ── */}
      <ChatList
        chats={visibleChats}
        activeChatId={activeChatId}
        onSelectChat={handleSelectChat}
        onNewChat={handleNewChat}
        onDeleteChat={handleDeleteChat}
        onRenameChat={renameChat}
        onNewJiraChat={jiraConfigured ? () => setJiraModalOpen(true) : undefined}
        onSearchSelect={handleChatSearchSelect}
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
                  title={t('window.renameHint')}
                  onClick={() => {
                    setTitleDraft(activeChat.title);
                    setEditingTitle(true);
                  }}
                >
                  {activeChat.title}
                </h3>
              )}
              {!activeChat.notFound && !activeChat.loadError && modelOptions.length > 0 && (
                <ModelSelector
                  value={selectedModelId}
                  defaultId={modelConfig.defaultModel.id}
                  options={modelOptions}
                  disabled={isStreaming}
                  onChange={handleModelChange}
                />
              )}
              {activeChat.createdAt && (
                <div className="chat-meta">
                  {t('window.createdAt', { date: new Date(activeChat.createdAt).toLocaleString() })}
                </div>
              )}
            </div>
            {/* Search toggle button in header (Ctrl/Cmd+F) */}
            {canSearchChat && (
              <button
                className={`chat-header-search-btn ${inChatSearch.open ? 'chat-header-search-btn--active' : ''}`}
                onClick={() => (inChatSearch.open ? inChatSearch.close() : inChatSearch.openBar())}
                title={t('inChatSearch.open')}
              >
                <IconSearch size={14} />
              </button>
            )}
            {/* Attachment toggle button in header */}
            <button
              className={`chat-header-attachments-btn ${attachPanelOpen ? 'chat-header-attachments-btn--active' : ''}`}
              onClick={() => setAttachPanelOpen((v) => !v)}
              title={t('window.attachments')}
            >
              <IconPaperclip size={15} />
              {attachCount > 0 && <span className="attach-badge">{attachCount}</span>}
            </button>
            <button className="chat-header-delete" onClick={() => handleDeleteChat(activeChat.id)}>
              <IconTrash />
            </button>
          </div>
        )}

        {inChatSearch.open && canSearchChat && (
          <ChatSearchBar
            inputRef={inChatSearchInputRef}
            query={inChatSearch.query}
            onQueryChange={inChatSearch.setQuery}
            total={inChatSearch.total}
            activeIndex={inChatSearch.activeIndex}
            loading={inChatSearch.loading}
            onPrev={inChatSearch.goPrev}
            onNext={inChatSearch.goNext}
            onClose={inChatSearch.close}
          />
        )}

        {loadingMessages ? (
          <div className="loading-messages">{t('window.loadingMessages')}</div>
        ) : activeChat?.notFound || activeChat?.loadError ? (
          <div className="loading-messages" style={{ flexDirection: 'column', gap: '0.5rem' }}>
            <span style={{ fontSize: '2rem' }}>{activeChat?.notFound ? '🔍' : '⚠️'}</span>
            <span>{activeChat?.notFound ? t('window.notFoundTitle') : t('window.loadErrorTitle')}</span>
            <span style={{ fontSize: '0.8rem', opacity: 0.7 }}>
              {activeChat?.notFound
                ? t('window.notFoundDesc')
                : t('window.loadErrorDesc', { status: activeChat?.loadError })}
            </span>
          </div>
        ) : (
          <MessageList
            key={activeChatId}
            conversationId={activeChatId}
            messages={activeMessages}
            onNavigateToDoc={onNavigateToDoc}
            onLoadMore={handleLoadOlder}
            onRetry={handleRetryMessage}
            hasMore={!!activeChat?.hasMore}
            canLoadMore={!isStreaming}
            activeSearchMid={inChatSearch.activeMatchMid}
            searchQuery={inChatSearch.open ? inChatSearch.query.trim() : ''}
          />
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
              {activeChat?.notFound ? t('window.notFoundInputNote') : t('window.loadErrorInputNote')}
            </span>
          </div>
        ) : (
          <MessageInput
            onSend={handleSendMessage}
            onStop={handleStopGeneration}
            disabled={isStreaming}
            onAttach={() => attachFileRef.current?.click()}
            isEmpty={isChatEmpty && !loadingMessages}
            resetSignal={composerResetSignal}
            focusSignal={composerFocusSignal}
            chatId={activeChatId}
            initialText={getDraft(draftsRef.current, activeChatId)}
            onTextChange={(v) => handleComposerTextChange(activeChatId, v)}
          />
        )}
      </div>

      {/* ── Right panel: attachments ── */}
      {attachPanelOpen && (
        <div className="chat-attachment-panel">
          <div className="chat-attachment-panel__header">
            <span>📎 {t('window.attachments')}</span>
            <button
              className="chat-attachment-panel__close"
              onClick={() => setAttachPanelOpen(false)}
              title={t('common:close')}
            >
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
              <p className="chat-attachment-panel__empty">{t('window.selectChat')}</p>
            )}
          </div>
        </div>
      )}
      <CreateJiraChatModal
        open={jiraModalOpen}
        onClose={() => setJiraModalOpen(false)}
        onCreate={handleCreateJiraChat}
        confluenceConfigured={confluenceConfigured}
      />
      <ErrorModal
        open={!!chatErrorModal}
        icon={chatErrorModal?.notFound ? '🔍' : '⚠️'}
        title={chatErrorModal?.notFound ? t('errorModal.notFoundTitle') : t('errorModal.loadErrorTitle')}
        message={
          chatErrorModal?.notFound
            ? t('errorModal.notFoundMessage')
            : t('errorModal.loadErrorMessage', { suffix: errorModalSuffix })
        }
        onClose={() => setChatErrorModal(null)}
      />
      <ConfirmModal
        open={!!chatDeleteConfirm}
        icon="🗑️"
        title={t('deleteModal.title')}
        message={
          chatDeleteConfirm?.title
            ? t('deleteModal.messageNamed', { title: chatDeleteConfirm.title })
            : t('deleteModal.message')
        }
        confirmLabel={t('deleteModal.confirm')}
        cancelLabel={t('deleteModal.cancel')}
        onConfirm={confirmDeleteChat}
        onCancel={() => setChatDeleteConfirm(null)}
      />
      <ErrorModal
        open={busyNotice}
        icon="⏳"
        title={t('errorModal.busyTitle')}
        message={t('errorModal.busyMessage')}
        onClose={() => setBusyNotice(false)}
      />
      <ErrorModal
        open={deletedNotice}
        icon="🗑️"
        title={t('errorModal.deletedTitle')}
        message={t('errorModal.deletedMessage')}
        onClose={() => setDeletedNotice(false)}
      />
      <ErrorModal
        open={uploadErrorNotice}
        icon="⚠️"
        title={t('errorModal.uploadTitle')}
        message={t('window.uploadError')}
        onClose={() => setUploadErrorNotice(false)}
      />
      <ErrorModal
        open={!!deleteErrorNotice}
        icon="⚠️"
        title={t('errorModal.deleteErrorTitle')}
        message={t('errorModal.deleteErrorMessage', {
          suffix: deleteErrorNotice && deleteErrorNotice.status !== 'network' ? ` (${deleteErrorNotice.status})` : '',
        })}
        onClose={() => setDeleteErrorNotice(null)}
      />
    </div>
  );
};

export default ChatWindow;
