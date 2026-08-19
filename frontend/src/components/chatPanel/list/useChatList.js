import { useCallback, useEffect, useRef, useState } from 'react';
import i18n from '@/i18n/index';
import chatApi from '@/api/chatApi';
import { DRAFT_CHAT_ID } from '@/constants/storage';

/**
 * Владелец списка чатов: сам стейт, первичная загрузка с бэка и точечные правки
 * (переименование, обновление темы после ответа).
 *
 * Наружу отдаётся `getChats`, а не ref-зеркало: колбэкам нужен свежий список без
 * того, чтобы держать `chats` в зависимостях — иначе каждый из них пересоздавался
 * бы на каждый чанк стриминга. Ref при этом остаётся внутри хука.
 *
 * @param {object}   p
 * @param {string}   p.initialActiveChatId  активный чат на момент монтирования
 *                                          (из URL либо из памяти localStorage)
 * @param {string}   p.initialPropChatId    он же, но только если задан явно в URL:
 *                                          отличает «осознанную ссылку на чат» от
 *                                          запомненного id, который мог устареть
 * @param {Function} p.makeDraft            () => объект нового черновика
 * @param {Function} p.selectChat           (id, opts) => void — поднять выбор в навигацию
 */
export default function useChatList({ initialActiveChatId, initialPropChatId, makeDraft, selectChat }) {
  const [chats, setChats] = useState([]);

  // Зеркало для синхронного чтения из колбэков (см. про getChats выше).
  const chatsRef = useRef(chats);
  useEffect(() => {
    chatsRef.current = chats;
  }, [chats]);
  const getChats = useCallback(() => chatsRef.current, []);

  // Точечная правка одного чата. `patch` — объект либо функция от текущего чата,
  // когда новое значение зависит от старого.
  const patchChat = useCallback((id, patch) => {
    setChats((prev) =>
      prev.map((c) => (c.id === id ? { ...c, ...(typeof patch === 'function' ? patch(c) : patch) } : c)),
    );
  }, []);

  // Правка ленты сообщений одного чата: `fn` получает массив (никогда не null).
  const patchMessages = useCallback(
    (id, fn) => patchChat(id, (c) => ({ messages: fn(c.messages || []) })),
    [patchChat],
  );

  // Значения на момент монтирования: первичная загрузка сверяется с ними уже
  // после ответа сервера, когда активный чат мог смениться.
  const initialActiveChatIdRef = useRef(initialActiveChatId);
  const initialPropChatIdRef = useRef(initialPropChatId);
  // Защита разовой загрузки списка от двойного вызова под StrictMode.
  const didFetchChatsRef = useRef(false);

  useEffect(() => {
    if (didFetchChatsRef.current) return;
    didFetchChatsRef.current = true;
    const fetchChats = async () => {
      try {
        const data = await chatApi.listChats();
        const chatList = data.map((chat) => ({
          id: chat.conversationId,
          title: chat.topic || i18n.t('chat:window.defaultTitle'),
          messages: null,
          createdAt: chat.createdAt || null,
          // updatedAt/aiTopic не участвуют в списке — они нужны вкладке «Инфо»
          // правой панели, а отдельного запроса за метаданными чата там нет.
          updatedAt: chat.updatedAt || null,
          aiTopic: chat.aiTopic || null,
          model: chat.model || null,
          mode: chat.mode || null,
          project: chat.project || null,
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

  // Переименование чата.
  const renameChat = useCallback(
    async (chatId, newTitle) => {
      // Черновик на бэке не существует — PUT /chats/new/topic вернул бы 4xx.
      // Заголовок правим только локально; на бэк он уедет вместе с первым сообщением.
      if (chatId === DRAFT_CHAT_ID) {
        patchChat(chatId, { title: newTitle });
        return;
      }
      try {
        await chatApi.renameChat(chatId, newTitle);
        patchChat(chatId, { title: newTitle });
      } catch (err) {
        console.error('Ошибка переименования чата:', err);
      }
    },
    [patchChat],
  );

  // Смена модели чата. Храним выбранный id как есть (модель всегда явная).
  // Для черновика на бэке чата ещё нет — PUT откладываем, модель уедет с первым
  // сообщением. Локально правим сразу, не дожидаясь ответа: UI реагирует мгновенно,
  // а откатывать нечего — выбор всё равно уедет со следующим сообщением.
  const changeModel = useCallback(
    async (chatId, newId) => {
      if (!chatId) return;
      patchChat(chatId, { model: newId });
      if (chatId === DRAFT_CHAT_ID) return;
      try {
        await chatApi.updateModel(chatId, newId);
      } catch (err) {
        console.error('Ошибка смены модели чата:', err);
      }
    },
    [patchChat],
  );

  // Смена режима чата. Пустой id ('') → «без режима».
  const changeMode = useCallback(
    async (chatId, newId) => {
      if (!chatId) return;
      patchChat(chatId, { mode: newId || null });
      if (chatId === DRAFT_CHAT_ID) return;
      try {
        await chatApi.updateMode(chatId, newId);
      } catch (err) {
        console.error('Ошибка смены режима чата:', err);
      }
    },
    [patchChat],
  );

  // Смена проекта чата. Пустой id ('') → вернуться к дефолтному. На бэк не пишется:
  // выбор становится проектом чата только с отправленным сообщением (?project= на
  // прогоне) — так сохранённое значение всегда означает «на каком проекте шла
  // история», и именно с ним бэкенд сверяется, ставя маркер смены проекта.
  const changeProject = useCallback(
    (chatId, newId) => {
      if (!chatId) return;
      patchChat(chatId, { project: newId || null });
    },
    [patchChat],
  );

  // Фоновое обновление темы чата с бэкенда после ответа.
  const fetchAndUpdateTitle = useCallback(
    async (chatId) => {
      try {
        const data = await chatApi.getChatMeta(chatId);
        const newTitle = data.topic;
        patchChat(chatId, (chat) => ({
          ...(newTitle ? { title: newTitle } : {}),
          model: data.model ?? chat.model ?? null,
          mode: data.mode ?? chat.mode ?? null,
          // Проекта здесь нет намеренно: выбор в селекторе на бэк не пишется до отправки
          // (см. changeProject), и ответ сервера про него отстаёт — взяв его, мы бы откатывали
          // выбранный, но ещё не отправленный проект всякий раз, когда завершается прогон.
          // не затираем уже имеющийся createdAt, иначе берём из ответа
          createdAt: chat.createdAt ?? data.createdAt ?? null,
          // updatedAt, наоборот, всегда из ответа: ради него этот запрос
          // и делается после ответа ассистента (бэк двигает его на каждом
          // сообщении), иначе «Изменён» во вкладке «Инфо» застынет.
          updatedAt: data.updatedAt ?? chat.updatedAt ?? null,
          aiTopic: data.aiTopic ?? chat.aiTopic ?? null,
        }));
      } catch (err) {
        console.error('Ошибка обновления темы чата:', err);
      }
    },
    [patchChat],
  );

  return {
    chats,
    getChats,
    setChats,
    patchChat,
    patchMessages,
    renameChat,
    changeModel,
    changeMode,
    changeProject,
    fetchAndUpdateTitle,
  };
}
