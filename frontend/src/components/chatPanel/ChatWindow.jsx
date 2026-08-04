import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import attachmentApi from '../../api/attachmentApi';
import {
  STORAGE_KEY_ACTIVE_CHAT,
  STORAGE_KEY_LAST_MODEL,
  STORAGE_KEY_LAST_MODE,
  DRAFT_CHAT_ID,
} from '../../constants/storage';
import { OWNER_TYPE } from '../../constants/ownerType';
import { nextMessageId } from './messageId';
import useModelConfig from './useModelConfig';
import useModeConfig from './useModeConfig';
import useChatMessages from './useChatMessages';
import useChatEventStream from './useChatEventStream';
import useInChatSearch from './useInChatSearch';
import useChatDrafts from './useChatDrafts';
import useChatDeletion from './useChatDeletion';

import MessageList from './MessageList';
import MessageInput from './MessageInput';
import ChatList from './ChatList';
import ChatSearch from './ChatSearch';
import ChatHeader from './ChatHeader';
import ChatSearchBar from './ChatSearchBar';
import ChatInfo from './ChatInfo';
import AttachmentPanel from '../common/AttachmentPanel';
import WorkspaceLayout from '../common/WorkspaceLayout';
import useAttachmentCount from '../common/useAttachmentCount';
import { IconInfo, IconPaperclip, IconPlus } from '../../icons';
import { RIGHT_TAB } from '../../constants/rightTabs';
import { RETRY_MODE } from '../../constants/retryMode';
import './chatWindow.css';
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

const ChatWindow = ({
  onNavigateToDoc,
  isActive = true,
  activeChatId: propActiveChatId = null,
  onSelectChat,
  onDocChanged,
  onFileChanged,
  panels,
}) => {
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
  // `navigate: false` — выбор сделан не пользователем, а автоматикой загрузки:
  // тогда навигация лишь запомнит чат, но не утащит с открытого раздела (панель
  // чата смонтирована всегда, в том числе поверх /files и /knowledge).
  const selectChat = useCallback(
    (id, opts) => {
      setActiveChatId(id);
      if (id) localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, id);
      if (onSelectChat) onSelectChat(id, opts);
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
      mode: lastModeRef.current || null,
      draft: true,
    }),
    [],
  );
  // Вложения живут в правой панели рабочей области — её состояние приходит из
  // навигации (URL), поэтому локального attachPanelOpen здесь больше нет.
  const openAttachments = panels?.onRightTabChange;
  // Счётчик для бейджа. У черновика чата на бэке ещё нет — считать нечего.
  const [attachCount, setAttachCount] = useAttachmentCount(
    OWNER_TYPE.CHAT,
    activeChatId && activeChatId !== DRAFT_CHAT_ID ? activeChatId : null,
  );
  // Конфиг моделей и режимов грузится один раз — вынесено в отдельные хуки.
  const { modelConfig, modelOptions } = useModelConfig();
  const { modeOptions } = useModeConfig();
  // Последняя модель, с которой отправляли сообщение (живёт между перезагрузками).
  const lastModelRef = useRef(localStorage.getItem(STORAGE_KEY_LAST_MODEL) || null);
  // Последний выбранный режим ('' — без режима).
  const lastModeRef = useRef(localStorage.getItem(STORAGE_KEY_LAST_MODE) || '');
  // Модалка ошибки загрузки чата: null | { notFound: bool, status }
  const [chatErrorModal, setChatErrorModal] = useState(null);
  // Уведомление «в чате уже идёт генерация» (ответ сервера 409 на старт прогона).
  const [busyNotice, setBusyNotice] = useState(false);
  // Повтор пришёл слишком поздно: бэк ответил 422 — модель уже начала отвечать.
  const [retryUnavailableNotice, setRetryUnavailableNotice] = useState(false);
  // Уведомление «чат удалён в другой вкладке» (событие CHAT_DELETED из потока).
  const [deletedNotice, setDeletedNotice] = useState(false);
  // Уведомление об ошибке загрузки файла (вместо нативного alert).
  const [uploadErrorNotice, setUploadErrorNotice] = useState(false);
  // chatId, для которого POST /runs уже отправлен, но runId ещё не получен.
  // Закрывает окно между кликом «отправить» и ответом сервера: isStreaming
  // становится true синхронно, и ввод блокируется сразу, а не с приходом runId.
  const [pendingRunChatId, setPendingRunChatId] = useState(null);
  // Bump → очистить текст в MessageInput («удаление» черновика).
  const [composerResetSignal, setComposerResetSignal] = useState(0);
  // Bump → сфокусировать MessageInput (при активации панели чата).
  const [composerFocusSignal, setComposerFocusSignal] = useState(0);
  // Неотправленные черновики по чатам ({ chatId: text }, localStorage) — вынесено
  // в useChatDrafts (отложенная запись + flush на beforeunload/размонтирование).
  const { getDraftFor, handleTextChange: handleComposerTextChange, clearDraft, flushDrafts } = useChatDrafts();
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
  // chatId, заданный явно в URL (/chat/<id>) на момент монтирования. null — когда
  // в адресе чата нет (просто /chat, либо открыт другой раздел). Позволяет
  // отличить «осознанную ссылку на чат» от id, подставленного из localStorage
  // (он может быть устаревшим).
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
          // updatedAt/aiTopic не участвуют в списке — они нужны вкладке «Инфо»
          // правой панели, а отдельного запроса за метаданными чата там нет.
          updatedAt: chat.updatedAt || null,
          aiTopic: chat.aiTopic || null,
          model: chat.model || null,
          mode: chat.mode || null,
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
          if (!fromUrl) selectChat(DRAFT_CHAT_ID, { navigate: false });
        } else if (currentId && existsInList) {
          // Чат из URL/localStorage реально существует — открываем как есть.
          setChats(chatList);
          // Если id пришёл НЕ из адреса (запомненный чат из localStorage), навигация
          // о нём ещё не знает: без этого заход на `/` показывал бы чат, а адрес
          // оставался бы «голым» /chat — такой ссылкой не поделиться.
          if (!fromUrl) selectChat(currentId, { navigate: false });
        } else if (currentId && fromUrl) {
          // Явный ?chat=<id> в URL, которого больше нет (устаревшая ссылка) —
          // показываем «не найдено». Автоматически НЕ переключаемся: пользователь
          // перешёл по конкретной ссылке и должен увидеть, что чат недоступен.
          const placeholder = {
            id: currentId,
            title: '...',
            messages: null,
            createdAt: null,
            model: null,
            notFound: true,
          };
          setChats([placeholder, ...chatList]);
        } else {
          // Чат в URL не задан (его нет вовсе, либо id из localStorage устарел) —
          // ошибку НЕ показываем: открываем первый существующий чат, а если чатов
          // нет — стартуем с пустого черновика «new» (поведение как у «Новый чат»).
          // Автовыбор при загрузке — не переход: если открыт другой раздел
          // (deep link на файл/документ), пользователя оттуда не уводим.
          const firstId = chatList[0]?.id;
          if (firstId) {
            setChats(chatList);
            selectChat(firstId, { navigate: false });
          } else {
            setChats([makeDraft()]);
            selectChat(DRAFT_CHAT_ID, { navigate: false });
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
    // Черновик на бэке не существует — PUT /chats/new/topic вернул бы 4xx.
    // Заголовок правим только локально; на бэк он уедет вместе с первым сообщением.
    if (chatId === DRAFT_CHAT_ID) {
      setChats((prev) => prev.map((chat) => (chat.id === chatId ? { ...chat, title: newTitle } : chat)));
      return;
    }
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
                mode: data.mode ?? chat.mode ?? null,
                // не затираем уже имеющийся createdAt, иначе берём из ответа
                createdAt: chat.createdAt ?? data.createdAt ?? null,
                // updatedAt, наоборот, всегда из ответа: ради него этот запрос
                // и делается после ответа ассистента (бэк двигает его на каждом
                // сообщении), иначе «Изменён» во вкладке «Инфо» застынет.
                updatedAt: data.updatedAt ?? chat.updatedAt ?? null,
                aiTopic: data.aiTopic ?? chat.aiTopic ?? null,
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

  const activeChat = useMemo(() => chats.find((c) => c.id === activeChatId) || null, [chats, activeChatId]);
  const activeMessages = useMemo(() => activeChat?.messages || [], [activeChat]);

  // Идёт генерация в активном чате? Источник правды — runId чата (его ставит старт
  // прогона и снимает терминальное событие) ПЛЮС pendingRunChatId, закрывающий
  // окно до ответа сервера на POST /runs. Управляет блокировкой ввода и видом
  // кнопки (отправить ↔ остановить).
  const isStreaming = !!activeChat?.runId || pendingRunChatId === activeChatId;

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
      // Модалка поверх чата (детали tool-call, подтверждения и т.п.) — не открываем
      // find-бар чата под ней, иначе браузерный Ctrl+F внутри модалки не работает:
      // наш бар всплывает и перехватывает фокус первым.
      if (document.querySelector('[aria-modal="true"]')) return;
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

  // Выбранный режим чата. Нет режима / режим убран из конфига → «без режима» ('').
  const selectedModeId = useMemo(() => {
    const m = activeChat?.mode;
    return m && modeOptions.some((o) => o.id === m) ? m : '';
  }, [activeChat, modeOptions]);

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

  // Режим для отправки: выбранный у чата → последний → без режима (''). Значение
  // валидируем по конфигу (режим мог исчезнуть).
  const resolveModeForSend = useCallback(
    (chat) => {
      const selected = chat?.mode;
      if (selected && modeOptions.some((o) => o.id === selected)) return selected;
      if (lastModeRef.current && modeOptions.some((o) => o.id === lastModeRef.current)) return lastModeRef.current;
      return '';
    },
    [modeOptions],
  );

  // Идёт ли в чате прогон прямо сейчас. Локального runId недостаточно: он мог не успеть
  // приехать потоком, поэтому переспрашиваем бэк. Нужно там, где сбой запроса ещё не
  // означает, что генерация не началась (см. runConversation).
  const hasActiveRun = useCallback(async (conversationId) => {
    if (chatsRef.current.find((c) => c.id === conversationId)?.runId) return true;
    try {
      const active = await chatApi.getActiveRun(conversationId);
      return !!active?.runId;
    } catch {
      return false;
    }
  }, []);

  // Старт фонового прогона для уже показанного вопроса. Общий код для первой отправки
  // и для «Повторить»: бьёт POST /runs и обрабатывает исход — runId (идёт генерация),
  // 409 (занято), 422 (повторять нечего) или сбой запроса.
  //
  // retry: true — повтор упавшего прогона (RETRY_MODE.CONTINUE). Текста не передаём:
  // вопрос уже сохранён на бэке, ходом остаётся он же. Оптимистичного пузыря здесь нет
  // и clientMsgId не нужен — ошибочный пузырь снимет эхо USER_MESSAGE, одинаково во всех
  // вкладках. retryMid — пузырь, из которого нажали повтор: с него снимаем кнопку, если
  // бэк ответил «повторять уже нечего».
  const runConversation = useCallback(
    async (conversationId, { text = null, clientMsgId = null, model, mode, retry = false, retryMid = null }) => {
      // Запоминаем как «последнюю» — новый чат стартует именно с неё.
      if (model) {
        lastModelRef.current = model;
        try {
          localStorage.setItem(STORAGE_KEY_LAST_MODEL, model);
        } catch {
          /* ignore quota errors */
        }
      }
      // Режим запоминаем всегда (в т.ч. '' — сознательный сброс к «без режима»).
      lastModeRef.current = mode || '';
      try {
        localStorage.setItem(STORAGE_KEY_LAST_MODE, mode || '');
      } catch {
        /* ignore quota errors */
      }
      // Блокируем ввод сразу, не дожидаясь runId от сервера. Снимается в finally:
      // при успехе к этому моменту у чата уже стоит runId (isStreaming не мигает),
      // при 409/ошибке ввод разблокируется — отправку можно повторить.
      setPendingRunChatId(conversationId);
      try {
        const res = await chatApi.startRun(conversationId, text, { model, mode, clientMsgId, retry });
        const runId = res?.runId;
        // id сохранённого вопроса: проставляем оптимистичному пузырю как dbId — якорь для
        // поиска по чату (find-бар). Своё эхо USER_MESSAGE эта вкладка гасит по clientMsgId,
        // так что другого источника id у неё нет. На повторе оптимистичного пузыря нет —
        // там id уже стоит с первой отправки (или приедет эхом, которое ничем не гасится).
        const dbId = Number(res?.messageId);
        const patchedId = clientMsgId && Number.isFinite(dbId) ? dbId : null;
        // Помечаем чат активным прогоном → кнопка «остановить», блокировка ввода.
        // (RUN_STARTED из потока проставит то же самое, если опередит.)
        if (runId) {
          setChats((prev) =>
            prev.map((c) =>
              c.id === conversationId
                ? {
                    ...c,
                    runId,
                    messages: patchedId
                      ? (c.messages || []).map((m) => (m.clientMsgId === clientMsgId ? { ...m, dbId: patchedId } : m))
                      : c.messages,
                  }
                : c,
            ),
          );
        }
      } catch (error) {
        // Не наша заявка — генерация уже идёт (часто из другой вкладки). Откатываем
        // оптимистичный пузырь (если был) и сообщаем пользователю. Текущий прогон всё
        // равно «прилетит» потоком событий (RUN_STARTED) и покажет «остановить».
        if (error?.status === 409) {
          if (clientMsgId) {
            localClientIdsRef.current.delete(clientMsgId);
            setChats((prev) =>
              prev.map((c) =>
                c.id === conversationId
                  ? { ...c, messages: (c.messages || []).filter((m) => m.clientMsgId !== clientMsgId) }
                  : c,
              ),
            );
          }
          setBusyNotice(true);
          return;
        }
        console.error('Failed to start run:', error);
        // 422 — повторять уже нечего: чат ушёл вперёд (другая вкладка, гонка с событием).
        // Снимаем кнопку с этого пузыря: дальше диалог продолжается обычным сообщением.
        if (error?.status === 422) {
          setChats((prev) =>
            prev.map((c) =>
              c.id === conversationId
                ? {
                    ...c,
                    messages: (c.messages || []).map((m) => (m.mid === retryMid ? { ...m, retryMode: undefined } : m)),
                  }
                : c,
            ),
          );
          setRetryUnavailableNotice(true);
          return;
        }
        // Запрос не удался — но прогон мог всё-таки стартовать: POST дошёл, а ответ до нас
        // нет (обрыв, прокси, спящая вкладка). Тогда вопрос уже сохранён и генерация идёт,
        // а пузырь «ошибка + повторить» предложил бы отправить тот же вопрос второй раз.
        if (await hasActiveRun(conversationId)) return;
        // На повторе показывать нечего: пузырь с ошибкой и его кнопка никуда не делись —
        // состояние чата не изменилось, повтор можно нажать ещё раз.
        if (retry) return;
        // Прогон не идёт, но он мог успеть и стартовать, и завершиться. Снимаем гашение
        // своего эха: если события с вопросом и ответом всё-таки придут, USER_MESSAGE
        // опознает наш пузырь по тексту и срежет всё, что показано после него, — вместе
        // с этой ошибкой. Не придут — останется ошибка с повтором по тексту вопроса.
        localClientIdsRef.current.delete(clientMsgId);
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
                      retryMode: RETRY_MODE.RESEND,
                      retryText: text,
                    },
                  ],
                }
              : c,
          ),
        );
      } finally {
        setPendingRunChatId((cur) => (cur === conversationId ? null : cur));
      }
    },
    [hasActiveRun],
  );

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
      const modeForSend = resolveModeForSend(chatForSend);

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
          mode: modeForSend || found.mode || null,
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

      await runConversation(conversationId, {
        text,
        clientMsgId,
        model: modelForSend,
        mode: modeForSend,
      });
    },
    [activeChatId, selectChat, resolveModelForSend, resolveModeForSend, runConversation, clearDraft],
  );

  // Повтор после ошибки. Что именно значит «Повторить», решено ещё в момент ошибки
  // (constants/retryMode.js) — только там известно, доехал ли вопрос до бэка:
  //   • CONTINUE — вопрос сохранён, а ответа нет ни одного: прогон запускается поверх той
  //     же истории, второго USER-сообщения не появляется. Ошибочный пузырь снимет эхо
  //     USER_MESSAGE — сразу во всех вкладках, поэтому локально его не трогаем.
  //   • RESEND — сбой самого POST /runs: вопрос не сохранён, отправляем его текст заново.
  //     Пузырь пользователя уже на месте — новый не добавляем, эхо гасится по clientMsgId.
  // Пузырей без retryMode здесь не бывает: у них нет и кнопки (см. MessageList).
  // Пузырь ищем по mid, а не по индексу в массиве: догрузка старых страниц
  // добавляет сообщения В НАЧАЛО списка, и индекс из замыкания рендера успел бы
  // устареть — фильтр по индексу снял бы не тот пузырь.
  const handleRetryMessage = useCallback(
    (mid) => {
      const chat = chatsRef.current.find((c) => c.id === activeChatId);
      // Во время генерации/ожидания старта В ЭТОМ чате повтор недоступен;
      // pending в другом чате повтору здесь не мешает (как и в isStreaming).
      if (!chat || chat.runId || pendingRunChatId === activeChatId) return;
      const target = (chat.messages || []).find((m) => m.mid === mid);
      if (!target || target.sender !== 'ai' || !target.error) return;
      const model = resolveModelForSend(chat);
      const mode = resolveModeForSend(chat);

      if (target.retryMode === RETRY_MODE.CONTINUE) {
        runConversation(activeChatId, { retry: true, retryMid: mid, model, mode });
        return;
      }
      if (target.retryMode !== RETRY_MODE.RESEND) return;
      const text = target.retryText;
      if (!text || !text.trim()) return;
      const clientMsgId = generateUUID();
      localClientIdsRef.current.add(clientMsgId);
      // Снимаем ошибочный AI-пузырь, чтобы не копить ошибки.
      setChats((prev) =>
        prev.map((c) =>
          c.id === activeChatId ? { ...c, messages: (c.messages || []).filter((m) => m.mid !== mid) } : c,
        ),
      );
      runConversation(activeChatId, { text, clientMsgId, model, mode });
    },
    [activeChatId, pendingRunChatId, resolveModelForSend, resolveModeForSend, runConversation],
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
      // Событие пришло извне (другая вкладка/сессия), а не от пользователя:
      // если он сейчас в файлах или базе знаний — не утаскиваем его в чат.
      selectChat(remaining[0]?.id || null, { navigate: false });
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
    onDocChanged,
    onFileChanged,
  });

  const handleNewChat = useCallback(() => {
    // Создаём черновик: реального id ещё нет (в URL будет 'new'), на бэк ничего
    // не пишем. UUID и запись в БД появятся при отправке первого сообщения.
    // Держим максимум один черновик в списке.
    setChats((prev) => [makeDraft(), ...prev.filter((c) => c.id !== DRAFT_CHAT_ID)]);
    selectChat(DRAFT_CHAT_ID);
    // (attachment panel stays as-is on new chat)
  }, [selectChat, makeDraft]);

  const {
    chatDeleteConfirm,
    deleteErrorNotice,
    requestDeleteChat,
    confirmDeleteChat,
    cancelDeleteChat,
    dismissDeleteErrorNotice,
  } = useChatDeletion({
    chatsRef,
    activeChatId,
    selectChat,
    setChats,
    clearDraft,
    handleNewChat,
    setChatErrorModal,
    locallyDeletingRef,
  });

  const handleDeleteChat = useCallback(
    (id) => {
      if (id === DRAFT_CHAT_ID) {
        // У черновика нет сущности на бэке — «удаление» лишь очищает поле ввода.
        // Сам черновик и выбранная модель остаются.
        clearDraft(DRAFT_CHAT_ID);
        setComposerResetSignal((n) => n + 1);
        return;
      }
      requestDeleteChat(id);
    },
    [clearDraft, requestDeleteChat],
  );

  const handleSelectChat = useCallback(
    (id) => {
      if (id === activeChatId) return;
      // Раньше здесь стояла блокировка переключения при наборе >3 символов — из-за
      // неё выбор чата в списке «переставал работать». Теперь черновик каждого чата
      // сохраняется отдельно (см. useChatDrafts), поэтому переключаться можно свободно.
      flushDrafts(); // зафиксировать текущий черновик до ухода
      selectChat(id);
      // Счётчик вложений сбрасывать вручную не нужно: useAttachmentCount сам
      // обнуляет его при смене владельца и запрашивает новое число.
    },
    [activeChatId, selectChat, flushDrafts],
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

  // Смена режима активного чата. Пустой id ('') → «без режима». Для черновика на бэке
  // чата ещё нет — PUT откладываем, режим уедет с первым сообщением.
  const handleModeChange = useCallback(
    async (newId) => {
      if (!activeChatId) return;
      setChats((prev) => prev.map((c) => (c.id === activeChatId ? { ...c, mode: newId || null } : c)));
      if (activeChatId === DRAFT_CHAT_ID) return;
      try {
        await chatApi.updateMode(activeChatId, newId);
      } catch (err) {
        console.error('Ошибка смены режима чата:', err);
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
        // Показываем загруженный файл — раскрываем правую панель на вложениях.
        openAttachments?.(RIGHT_TAB.ATTACHMENTS);
      } catch (err) {
        console.error('Upload error:', err);
        setUploadErrorNotice(true);
      }
    },
    [activeChatId, openAttachments, setAttachCount],
  );

  // Суффикс с кодом ошибки для сообщения модалки (если это не сетевой сбой).
  const errorModalSuffix = chatErrorModal && chatErrorModal.status !== 'network' ? ` (${chatErrorModal.status})` : '';

  // ── Центр: шапка чата, найденное, лента сообщений и поле ввода ──────────────
  const center = (
    <>
      {activeChat && (
        <ChatHeader
          chat={activeChat}
          canSearch={canSearchChat}
          searchOpen={inChatSearch.open}
          onToggleSearch={() => (inChatSearch.open ? inChatSearch.close() : inChatSearch.openBar())}
          onRename={renameChat}
          onDelete={handleDeleteChat}
        />
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
          initialText={getDraftFor(activeChatId)}
          onTextChange={(v) => handleComposerTextChange(activeChatId, v)}
          model={{
            config: modelConfig,
            options: modelOptions,
            selected: selectedModelId,
            onChange: handleModelChange,
          }}
          mode={{
            options: modeOptions,
            selected: selectedModeId,
            onChange: handleModeChange,
          }}
        />
      )}
    </>
  );

  // Подписи модели и режима для вкладки «Инфо»: в чате хранятся id, а показывать
  // осмысленно человекочитаемый label из конфига.
  const selectedModelLabel = useMemo(
    () => modelOptions.find((o) => o.id === selectedModelId)?.label || selectedModelId || null,
    [modelOptions, selectedModelId],
  );
  const selectedModeLabel = useMemo(
    () => modeOptions.find((o) => o.id === selectedModeId)?.label || null,
    [modeOptions, selectedModeId],
  );

  // Мемо ниже держится на этом срезе, а не на самом activeChat: объект чата
  // пересоздаётся на каждый чанк стриминга (в нём лежат messages), и вкладка
  // «Инфо» тянула бы за собой пересборку всей правой панели. Поля здесь —
  // примитивы, меняются только когда меняются реально.
  const chatTitle = activeChat?.title ?? null;
  const chatAiTopic = activeChat?.aiTopic ?? null;
  const chatCreatedAt = activeChat?.createdAt ?? null;
  const chatUpdatedAt = activeChat?.updatedAt ?? null;
  const infoChat = useMemo(
    () =>
      activeChatId
        ? {
            id: activeChatId,
            title: chatTitle,
            aiTopic: chatAiTopic,
            createdAt: chatCreatedAt,
            updatedAt: chatUpdatedAt,
          }
        : null,
    [activeChatId, chatTitle, chatAiTopic, chatCreatedAt, chatUpdatedAt],
  );

  // ── Правая панель: инфо о чате + вложения ──────────────────────────────────
  // Мемоизируем: ChatWindow перерисовывается на каждый чанк стриминга, а без
  // этого на каждый чанк пересоздавалось бы и содержимое открытой панели
  // вложений (таблица со списком файлов).
  const rightTabs = useMemo(
    () => [
      {
        // «Инфо» первой — так же, как в базе знаний и файлах.
        key: RIGHT_TAB.INFO,
        label: t('tabs.info'),
        icon: <IconInfo size={16} />,
        content: <ChatInfo chat={infoChat} modelLabel={selectedModelLabel} modeLabel={selectedModeLabel} />,
      },
      {
        key: RIGHT_TAB.ATTACHMENTS,
        label: t('window.attachments'),
        icon: <IconPaperclip size={16} />,
        badge: attachCount,
        content: activeChatId ? (
          <AttachmentPanel
            key={activeChatId}
            ownerType={OWNER_TYPE.CHAT}
            ownerId={activeChatId}
            onCountChange={setAttachCount}
          />
        ) : (
          <p className="chat-empty-note">{t('window.selectChat')}</p>
        ),
      },
    ],
    [t, attachCount, activeChatId, setAttachCount, infoChat, selectedModelLabel, selectedModeLabel],
  );

  return (
    <>
      <WorkspaceLayout
        {...panels}
        left={{
          title: t('list.title'),
          action: (
            <button type="button" onClick={handleNewChat} className="btn btn--primary">
              <IconPlus />
              {t('list.newChat')}
            </button>
          ),
          toolbar: <ChatSearch onSelect={handleChatSearchSelect} />,
          children: (
            <ChatList
              chats={visibleChats}
              activeChatId={activeChatId}
              onSelectChat={handleSelectChat}
              onDeleteChat={handleDeleteChat}
            />
          ),
        }}
        center={center}
        right={rightTabs}
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
        onCancel={cancelDeleteChat}
      />
      <ErrorModal
        open={busyNotice}
        icon="⏳"
        title={t('errorModal.busyTitle')}
        message={t('errorModal.busyMessage')}
        onClose={() => setBusyNotice(false)}
      />
      <ErrorModal
        open={retryUnavailableNotice}
        icon="↻"
        title={t('errorModal.retryUnavailableTitle')}
        message={t('errorModal.retryUnavailableMessage')}
        onClose={() => setRetryUnavailableNotice(false)}
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
        onClose={dismissDeleteErrorNotice}
      />
    </>
  );
};

export default ChatWindow;
