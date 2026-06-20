import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import { openChatEventStream } from '../../api/chatEvents';
import { applyChatEvent } from './chatEventReducer';
import attachmentApi from '../common/attachmentApi';
import { STORAGE_KEY_ACTIVE_CHAT, STORAGE_KEY_LAST_MODEL, DRAFT_CHAT_ID } from '../../constants/storage';
import { CHAT_PAGE_SIZE as PAGE_SIZE } from '../../constants/pagination';

import MessageList from './MessageList';
import MessageInput from './MessageInput';
import ChatList from './ChatList';
import ModelSelector from './ModelSelector';
import AttachmentPanel from '../common/AttachmentPanel';
import { IconPaperclip, IconTrash } from '../knowledgeBasePanel/icons';
import './chatWindow.css';
import '../common/attachmentPanel.css';
import CreateJiraChatModal from './CreateJiraChatModal';
import './createJiraChatModal.css';
import JiraAttachmentPanel from './JiraAttachmentPanel';
import './jiraAttachmentPanel.css';
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

const metaToCall = (x) => ({
  name: x.name,
  arguments: x.arguments,
  status: x.status,
  error: x.error,
  resultMeta: x.resultMeta,
});

// Превращает «сырые» сообщения с бэка (хронологический порядок) в пузыри для рендера.
// Системные сообщения-«крошки» (toolInvocationMetas из ChatMemoryService.saveToolCalls)
// пузырём не показываем, а прикрепляем к предыдущему ответу ассистента — это даёт
// resultMeta для блока «изменения документа».
// Если крошка идёт в самом начале страницы (её ассистент остался в более старой,
// ещё не загруженной странице) — её metas возвращаются в leadingMetas, чтобы прицепить
// их позже, когда догрузим страницу с этим ассистентом (см. attachLeadingMetas).
const transformPage = (rawMsgs) => {
  const bubbles = [];
  const leadingMetas = [];
  let sawAi = false;
  for (const m of rawMsgs || []) {
    const type = m.type?.toLowerCase?.();
    if (type === 'system') {
      const metas = m.toolInvocationMetas;
      if (Array.isArray(metas) && metas.length) {
        const prev = bubbles[bubbles.length - 1];
        if (sawAi && prev?.sender === 'ai') {
          prev.toolCalls = [...(prev.toolCalls || []), ...metas.map(metaToCall)];
        } else {
          // Ассистент этой крошки — в более старой странице: несём metas наверх.
          leadingMetas.push(...metas.map(metaToCall));
        }
      }
      continue; // преамбулу как сообщение не рендерим
    }
    if (type !== 'user') sawAi = true;
    bubbles.push({ text: m.content, sender: type === 'user' ? 'user' : 'ai' });
  }
  return { bubbles, leadingMetas };
};

// Прицепляет «висячие» metas (крошки без ассистента в своей странице) к последнему
// AI-пузырю переданного набора. Возвращает остаток, который не удалось прицепить
// (если в наборе вообще нет ассистента) — его несём дальше вверх.
const attachLeadingMetas = (bubbles, metas) => {
  if (!metas || !metas.length) return [];
  for (let i = bubbles.length - 1; i >= 0; i--) {
    if (bubbles[i].sender === 'ai') {
      bubbles[i] = { ...bubbles[i], toolCalls: [...(bubbles[i].toolCalls || []), ...metas] };
      return [];
    }
  }
  return metas;
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
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [attachPanelOpen, setAttachPanelOpen] = useState(false);
  const [attachCount, setAttachCount] = useState(0);
  const [jiraModalOpen, setJiraModalOpen] = useState(false);
  // Список выбираемых моделей и дефолтная: { defaultModel: {id,label}, models: [{id,label}] }
  const [modelConfig, setModelConfig] = useState(null);
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
  // Bump → очистить текст в MessageInput («удаление» черновика).
  const [composerResetSignal, setComposerResetSignal] = useState(0);
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

  // Список выбираемых моделей (GET /api/chats/models). Грузим один раз.
  useEffect(() => {
    let cancelled = false;
    chatApi
      .getModels()
      .then((cfg) => {
        if (!cancelled) setModelConfig(cfg);
      })
      .catch((err) => console.error('Ошибка загрузки списка моделей:', err));
    return () => {
      cancelled = true;
    };
  }, []);

  // Источник правды — проп из навигации. Когда он меняется (клик по вкладке,
  // popstate, восстановление из URL), подхватываем активный чат.
  useEffect(() => {
    if (propActiveChatId && propActiveChatId !== activeChatId) {
      setActiveChatId(propActiveChatId);
      localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, propActiveChatId);
    }
  }, [propActiveChatId]); // eslint-disable-line react-hooks/exhaustive-deps

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

  // Ref для защиты от повторных попыток по chatId, которых нет в списке chats
  const failedChatIdsRef = useRef(new Set());
  // Защита от параллельных догрузок старых сообщений для одного и того же чата.
  const loadingOlderRef = useRef(new Set());
  // Защита от параллельных начальных загрузок сообщений одного чата.
  // Без неё при старте страницы loadMessages вызывается дважды: первый раз когда
  // chats=[] (до загрузки списка), второй — когда setChats(chatList) меняет стейт.
  const loadingMessagesRef = useRef(new Set());

  // Загрузка сообщений: последняя страница (PAGE_SIZE) + метаданные чата.
  // Метаданные (model/topic) берём отдельным лёгким запросом includeMessages=false,
  // сами сообщения — пагинированным /messages. Это не тащит весь длинный чат.
  const loadMessages = useCallback(async (chatId) => {
    if (loadingMessagesRef.current.has(chatId)) return;
    loadingMessagesRef.current.add(chatId);
    setLoadingMessages(true);
    try {
      const [meta, page] = await Promise.all([chatApi.getChatMeta(chatId), chatApi.getMessages(chatId, PAGE_SIZE)]);
      const { bubbles, leadingMetas } = transformPage(page.messages);

      failedChatIdsRef.current.delete(chatId);
      setChats((prev) =>
        prev.map((chat) =>
          chat.id === chatId
            ? {
                ...chat,
                messages: bubbles,
                hasMore: !!page.hasMore,
                oldestCursor: page.oldestCursor || null,
                // metas, чей ассистент в ещё не загруженной более старой странице
                pendingLeadingMetas: leadingMetas,
                notFound: false,
                loadError: null,
                model: meta.model ?? null,
              }
            : chat,
        ),
      );
    } catch (err) {
      console.error('Ошибка загрузки сообщений:', err);
      const status = err.status || 'network';
      const isNotFound = status === 404;
      failedChatIdsRef.current.add(chatId);
      setChats((prev) =>
        prev.map((chat) =>
          chat.id === chatId ? { ...chat, messages: [], notFound: isNotFound, loadError: status } : chat,
        ),
      );
      setChatErrorModal({ notFound: isNotFound, status });
    } finally {
      loadingMessagesRef.current.delete(chatId);
      setLoadingMessages(false);
    }
  }, []);

  // Догрузка более старой страницы сообщений (вызывается при прокрутке вверх).
  // Возвращает true, если что-то догрузилось (нужно MessageList для коррекции скролла).
  const loadOlderMessages = useCallback(async (chatId) => {
    const chat = chatsRef.current.find((c) => c.id === chatId);
    if (!chat || !chat.hasMore || !chat.oldestCursor) return false;
    if (loadingOlderRef.current.has(chatId)) return false;
    loadingOlderRef.current.add(chatId);
    try {
      const page = await chatApi.getMessages(chatId, PAGE_SIZE, chat.oldestCursor);
      const { bubbles: olderBubbles, leadingMetas } = transformPage(page.messages);
      if (!olderBubbles.length && (!leadingMetas || !leadingMetas.length)) {
        // Пустая страница — больше грузить нечего.
        setChats((prev) => prev.map((c) => (c.id === chatId ? { ...c, hasMore: false } : c)));
        return false;
      }

      setChats((prev) =>
        prev.map((c) => {
          if (c.id !== chatId) return c;
          const merged = olderBubbles.slice();
          // Крошки с прошлой (более новой) границы — их ассистент мог оказаться
          // в этой странице. Прицепляем; что не прицепилось — несём дальше вверх.
          const carry = attachLeadingMetas(merged, c.pendingLeadingMetas);
          return {
            ...c,
            messages: [...merged, ...(c.messages || [])],
            hasMore: !!page.hasMore,
            oldestCursor: page.oldestCursor || c.oldestCursor,
            pendingLeadingMetas: [...(leadingMetas || []), ...carry],
          };
        }),
      );
      return true;
    } catch (err) {
      console.error('Ошибка догрузки старых сообщений:', err);
      return false;
    } finally {
      loadingOlderRef.current.delete(chatId);
    }
  }, []);

  const handleLoadOlder = useCallback(() => loadOlderMessages(activeChatId), [activeChatId, loadOlderMessages]);

  useEffect(() => {
    if (activeChatId && activeChatId !== DRAFT_CHAT_ID) {
      const chat = chats.find((c) => c.id === activeChatId);
      // Не загружаем если: сообщения уже есть, чат помечен как ошибочный,
      // или chatId уже в ref (защита когда чат ещё не появился в списке)
      const alreadyFailed = failedChatIdsRef.current.has(activeChatId);
      if (!chat?.messages && !chat?.notFound && !chat?.loadError && !alreadyFailed) {
        loadMessages(activeChatId);
      }
      // Сохраняем в localStorage только реально существующий чат
      if (!chat?.notFound && !chat?.loadError && !alreadyFailed) {
        localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, activeChatId);
      }
    }
  }, [activeChatId, chats, loadMessages]);

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

  // Список для сайдбара: черновик «new» не показываем, пока в нём нет сообщений.
  // Он промоутится в реальный чат (с UUID и draft:false) при отправке первого
  // сообщения — тогда и появляется пунктом в списке. В главном окне черновик при
  // этом остаётся активным (берётся из полного chats), печатать в него можно.
  const visibleChats = useMemo(() => chats.filter((c) => c.id !== DRAFT_CHAT_ID), [chats]);

  // Опции для селектора: модели из конфига + дефолтная (если её нет в списке — добавляем).
  const modelOptions = useMemo(() => {
    const def = modelConfig?.defaultModel;
    if (!def?.id) return [];
    const list = Array.isArray(modelConfig.models) ? [...modelConfig.models] : [];
    if (!list.some((m) => m.id === def.id)) list.unshift(def);
    return list;
  }, [modelConfig]);

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

      // Модель, с которой шлём. Всегда явная: выбранная у чата → последняя → дефолтная.
      const selected = chatForSend?.model;
      const modelForSend =
        selected && modelOptions.some((o) => o.id === selected)
          ? selected
          : lastModelRef.current && modelOptions.some((o) => o.id === lastModelRef.current)
          ? lastModelRef.current
          : modelConfig?.defaultModel?.id || null;
      // Запоминаем как «последнюю» — новый чат стартует именно с неё.
      if (modelForSend) {
        lastModelRef.current = modelForSend;
        try {
          localStorage.setItem(STORAGE_KEY_LAST_MODEL, modelForSend);
        } catch {
          /* ignore quota errors */
        }
      }

      // Оптимистично: промоутим черновик и показываем пузырь пользователя.
      // AI-пузырь не добавляем — его создаст событие RUN_STARTED.
      setChats((prev) => {
        const found = prev.find((c) => c.id === activeChatId);
        if (!found) return prev;
        const newMessages = [...(found.messages || []), { text, sender: 'user', clientMsgId }];
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
        // оптимистичный пузырь и сообщаем пользователю. Текущий прогон всё равно
        // «прилетит» в этот чат потоком событий (RUN_STARTED) и покажет «остановить».
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
        console.error('Failed to start run:', error);
        setChats((prev) =>
          prev.map((c) =>
            c.id === conversationId
              ? {
                  ...c,
                  runId: null,
                  messages: [...(c.messages || []), { text: tRef.current('window.genericError'), sender: 'ai' }],
                }
              : c,
          ),
        );
      }
    },
    [activeChatId, selectChat, modelConfig, modelOptions],
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
  useEffect(() => {
    const chatId = activeChatId;
    if (!chatId || chatId === DRAFT_CHAT_ID) return undefined;
    const chat = chatsRef.current.find((c) => c.id === chatId);
    if (!chat || !Array.isArray(chat.messages) || chat.notFound || chat.loadError) return undefined;

    const ctx = {
      isLocal: (id) => localClientIdsRef.current.has(id),
      stoppedLabel: tRef.current('window.stopped'),
      errorLabel: tRef.current('window.genericError'),
      interruptedNote: `\n\n_**${tRef.current('message.interrupted')}**_`,
    };
    return openChatEventStream(chatId, {
      onEvent: (ev) => {
        if (ev.type === 'CHAT_DELETED') {
          handleRemoteChatDeleted(chatId);
          return;
        }
        setChats((prev) => prev.map((c) => (c.id === chatId ? applyChatEvent(c, ev, ctx) : c)));
        if (ev.type === 'RUN_DONE' || ev.type === 'RUN_STOPPED' || ev.type === 'RUN_ERROR') {
          fetchAndUpdateTitle(chatId);
        }
      },
    });
  }, [activeChatId, activeMessagesReady, fetchAndUpdateTitle, handleRemoteChatDeleted]);

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
        setComposerResetSignal((n) => n + 1);
        return;
      }
      if (chats.length <= 1) return;
      const chat = chats.find((c) => c.id === id);
      setChatDeleteConfirm({ id, title: chat?.title ?? '' });
    },
    [chats],
  );

  // Реальное удаление — после подтверждения в модалке.
  const confirmDeleteChat = useCallback(async () => {
    const target = chatDeleteConfirm;
    setChatDeleteConfirm(null);
    if (!target) return;
    const { id } = target;
    // Помечаем как «наше» удаление — эхо CHAT_DELETED по потоку не покажет нам модалку.
    locallyDeletingRef.current.add(id);
    await chatApi.deleteChat(id);
    setChats((prev) => prev.filter((chat) => chat.id !== id));
    if (activeChatId === id) {
      const remaining = chatsRef.current.filter((chat) => chat.id !== id);
      const newActiveId = remaining[0]?.id || null;
      selectChat(newActiveId);
    }
  }, [chatDeleteConfirm, activeChatId, selectChat]);

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      selectChat(id);
      setAttachCount(0); // reset until new panel loads count for new chat
    },
    [activeChatId, selectChat],
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
        alert(t('window.uploadError'));
      }
    },
    [activeChatId, t],
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
                  title={t('window.renameHint')}
                  onClick={() => {
                    setTitleDraft(activeChat.title);
                    setEditingTitle(true);
                  }}
                >
                  ️{activeChat.title}
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
            messages={activeMessages}
            onNavigateToDoc={onNavigateToDoc}
            onLoadMore={handleLoadOlder}
            hasMore={!!activeChat?.hasMore}
            canLoadMore={!isStreaming}
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
            chatId={activeChatId}
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
    </div>
  );
};

export default ChatWindow;
