import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
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
  const [isLoading, setIsLoading] = useState(false);

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
  // Bump → очистить текст в MessageInput («удаление» черновика).
  const [composerResetSignal, setComposerResetSignal] = useState(0);
  const aiMessageTextRef = useRef('');
  const aiMessageIndexRef = useRef(-1);
  const abortControllerRef = useRef(null);
  // Tool calls accumulated for the current AI message segment
  const toolCallsRef = useRef([]);
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

      // Черновик: настоящий conversationId (UUID) рождается именно сейчас.
      // Для обычного чата conversationId === activeChatId.
      const isDraft = activeChatId === DRAFT_CHAT_ID;
      const conversationId = isDraft ? generateUUID() : activeChatId;

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

      setChats((prev) => {
        const found = prev.find((c) => c.id === activeChatId);
        if (!found) return prev;
        const newMessages = [...(found.messages || []), { text, sender: 'user' }, { text: '', sender: 'ai' }];
        // Промоушен черновика: присваиваем реальный id и проставляем явную модель.
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

      setIsLoading(true);
      aiMessageTextRef.current = '';
      aiMessageIndexRef.current = initialAiIndex;
      toolCallsRef.current = [];

      const abortController = new AbortController();
      abortControllerRef.current = abortController;

      try {
        const params = modelForSend ? `?model=${encodeURIComponent(modelForSend)}` : '';
        const url = `/api/chats/${encodeURIComponent(conversationId)}/messages/stream${params}`;
        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'text/event-stream' },
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
                  const chatIndex = prev.findIndex((c) => c.id === conversationId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  const idx = aiMessageIndexRef.current;
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: aiMessageTextRef.current };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== conversationId);
                  return [updated, ...others];
                });
                fetchAndUpdateTitle(conversationId);
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
                        ? {
                            ...t,
                            status: tc.status,
                            error: tc.error,
                            resultGist: tc.resultGist ?? t.resultGist,
                            resultMeta: tc.resultMeta ?? t.resultMeta,
                          }
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
                        resultMeta: tc.resultMeta,
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
                          resultMeta: tc.resultMeta,
                        },
                      ];
                    } else {
                      toolCallsRef.current = toolCallsRef.current.map((t) =>
                        t.name === tc.name && JSON.stringify(t.arguments || {}) === argsKey
                          ? {
                              ...t,
                              status: tc.status,
                              error: tc.error,
                              resultGist: tc.resultGist ?? t.resultGist,
                              resultMeta: tc.resultMeta ?? t.resultMeta,
                            }
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
                  const chatIndex = prev.findIndex((c) => c.id === conversationId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: finalText, toolCalls: finalToolCalls };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== conversationId);
                  return [updated, ...others];
                });
                fetchAndUpdateTitle(conversationId);
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
                  const chatIndex = prev.findIndex((c) => c.id === conversationId);
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
                  const others = prev.filter((c) => c.id !== conversationId);
                  return [updated, ...others];
                });
              } else if (textChanged || toolCallChanged) {
                const idx = aiMessageIndexRef.current;
                const currentText = aiMessageTextRef.current;
                const currentToolCalls = [...toolCallsRef.current];
                setChats((prev) => {
                  const chatIndex = prev.findIndex((c) => c.id === conversationId);
                  if (chatIndex === -1) return prev;
                  const updated = { ...prev[chatIndex] };
                  const messages = [...updated.messages];
                  if (messages[idx]?.sender === 'ai') {
                    messages[idx] = { ...messages[idx], text: currentText, toolCalls: currentToolCalls };
                    updated.messages = messages;
                  }
                  const others = prev.filter((c) => c.id !== conversationId);
                  return [updated, ...others];
                });
              }
            }
          }
        }

        // Поток завершился без [DONE]
        aiMessageTextRef.current = aiMessageTextRef.current.trimEnd();
        setChats((prev) => {
          const chatIndex = prev.findIndex((c) => c.id === conversationId);
          if (chatIndex === -1) return prev;
          const updated = { ...prev[chatIndex] };
          const messages = [...updated.messages];
          const idx = aiMessageIndexRef.current;
          if (messages[idx]?.sender === 'ai') {
            messages[idx] = { ...messages[idx], text: aiMessageTextRef.current };
            updated.messages = messages;
          }
          const others = prev.filter((c) => c.id !== conversationId);
          return [updated, ...others];
        });
        fetchAndUpdateTitle(conversationId);
      } catch (error) {
        if (error.name === 'AbortError') {
          console.log('Stream aborted');
          setChats((prev) => {
            const chatIndex = prev.findIndex((c) => c.id === conversationId);
            if (chatIndex === -1) return prev;
            const updated = { ...prev[chatIndex] };
            const messages = [...updated.messages];
            const idx = aiMessageIndexRef.current;
            if (messages[idx]?.sender === 'ai') {
              messages[idx] = {
                ...messages[idx],
                text: (aiMessageTextRef.current || '').trimEnd() + ' ' + tRef.current('window.stopped'),
              };
              updated.messages = messages;
            }
            const others = prev.filter((c) => c.id !== conversationId);
            return [updated, ...others];
          });
        } else {
          console.error('Failed to send message:', error);
          setChats((prev) => {
            const chatIndex = prev.findIndex((c) => c.id === conversationId);
            if (chatIndex === -1) return prev;
            const updated = { ...prev[chatIndex] };
            const messages = [...updated.messages];
            const idx = aiMessageIndexRef.current;
            if (messages[idx]?.sender === 'ai') {
              const partial = (aiMessageTextRef.current || '').trimEnd();
              // Пометку оборачиваем в markdown тут, а переводим только текст.
              const note = `\n\n_**${tRef.current('message.interrupted')}**_`;
              messages[idx] = {
                ...messages[idx],
                // если что-то успело прийти — оставляем и дописываем пометку,
                // иначе показываем обычный текст ошибки
                text: partial ? partial + note : tRef.current('window.genericError'),
                toolCalls: [...toolCallsRef.current],
              };
              updated.messages = messages;
            }
            const others = prev.filter((c) => c.id !== conversationId);
            return [updated, ...others];
          });
        }
      } finally {
        setIsLoading(false);
        abortControllerRef.current = null;
      }
    },
    [activeChatId, fetchAndUpdateTitle, selectChat, modelConfig, modelOptions],
  );

  const handleStopGeneration = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  }, []);

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
                  disabled={isLoading}
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
            canLoadMore={!isLoading}
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
            disabled={isLoading}
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
    </div>
  );
};

export default ChatWindow;
