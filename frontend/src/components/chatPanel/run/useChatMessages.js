import { useCallback, useEffect, useRef, useState } from 'react';
import chatApi from '@/api/chatApi';
import { STORAGE_KEY_ACTIVE_CHAT, DRAFT_CHAT_ID } from '@/constants/storage';
import { CHAT_PAGE_SIZE as PAGE_SIZE } from '@/constants/pagination';
import { fetchRunState, IDLE_RUN_STATE } from './activeRun';
import { transformPage, trimActiveRunTail, attachLeadingMetas } from './messagesPage';

/**
 * Загрузка и пагинация сообщений активного чата. Владеет своими защитными ref-ами
 * (повторные/параллельные загрузки) и состоянием загрузки; сами сообщения пишет в общий
 * стейт чатов через переданный setChats. Возвращаемый loadingMessages — про активный чат:
 * загрузка соседнего заглушку в нём не зажигает.
 *
 * @param {object}   p
 * @param {Array}    p.chats          текущий список чатов (для триггер-эффекта)
 * @param {Function} p.getChats       () => чаты: свежий снимок для колбэков
 * @param {Function} p.setChats       сеттер общего стейта чатов
 * @param {string}   p.activeChatId   id активного чата
 * @param {Function} p.onLoadError    ({ notFound, status }) => void — показать модалку
 * @returns {{ loadingMessages: boolean,
 *             loadMessages: (id:string)=>Promise<Array|undefined>,
 *             loadOlderMessages: (id:string)=>Promise<boolean>,
 *             failedChatIdsRef: object }}
 */
export default function useChatMessages({ chats, getChats, setChats, activeChatId, onLoadError }) {
  // Какие чаты грузятся прямо сейчас. Множество, а не один флаг: загрузки идут
  // параллельно (переключение чатов, догоняющая сверка прогона), и «грузится» на экране
  // обязано означать «грузится ЭТОТ чат» — общий флаг зажигал бы заглушку в открытом чате
  // из-за загрузки соседнего и гасил бы её, когда та первой заканчивалась.
  const [loadingChatIds, setLoadingChatIds] = useState(() => new Set());
  const markLoading = useCallback((chatId, loading) => {
    setLoadingChatIds((prev) => {
      if (prev.has(chatId) === loading) return prev;
      const next = new Set(prev);
      if (loading) next.add(chatId);
      else next.delete(chatId);
      return next;
    });
  }, []);

  // Ref для защиты от повторных попыток по chatId, которых нет в списке chats.
  const failedChatIdsRef = useRef(new Set());
  // Защита от параллельных догрузок старых сообщений для одного и того же чата.
  const loadingOlderRef = useRef(new Set());
  // Защита от параллельных начальных загрузок сообщений одного чата.
  // Без неё при старте страницы loadMessages вызывается дважды: первый раз когда
  // chats=[] (до загрузки списка), второй — когда setChats(chatList) меняет стейт.
  // Не Set, а Map chatId → промис самой загрузки: параллельный вызов получает тот же
  // промис и дожидается страницы, вместо undefined. На нём построена догоняющая сверка
  // прогона (useChatEventStream) — ей нужен результат, а не «кто-то уже грузит».
  const loadingMessagesRef = useRef(new Map());

  // onLoadError может меняться между рендерами — держим в ref, чтобы loadMessages
  // оставался стабильным (его кладут в deps других эффектов/колбэков).
  const onLoadErrorRef = useRef(onLoadError);
  useEffect(() => {
    onLoadErrorRef.current = onLoadError;
  }, [onLoadError]);

  // Загрузка сообщений: последняя страница (PAGE_SIZE) + метаданные чата.
  // Метаданные (model/topic) берём отдельным лёгким запросом includeMessages=false,
  // сами сообщения — пагинированным /messages. Это не тащит весь длинный чат.
  //
  // Возвращает разобранную страницу — пузыри ДО обрезки хвоста активного прогона. Их
  // читает тот, кому мало самой загрузки: догоняющая сверка прогона достаёт из них вызовы
  // инструментов, чьи события прошли мимо (см. useChatEventStream). Именно необрезанные:
  // обрезка — про показ активного прогона, а мутации ищутся в ЗАВЕРШИВШЕМСЯ, чьи сегменты
  // повтор (RETRY_MODE.CONTINUE) оставляет как раз в срезаемом хвосте — нового
  // USER-сообщения он не добавляет. undefined — загрузка упала.
  const loadMessages = useCallback(
    (chatId) => {
      const inFlight = loadingMessagesRef.current.get(chatId);
      if (inFlight) return inFlight;
      const load = async () => {
        markLoading(chatId, true);
        try {
          // Занятость чата — восстановление состояния после перезагрузки: если в чате прямо
          // сейчас идёт прогон, его частично сохранённые сегменты убираем из загруженной
          // истории (их пересоберёт SSE-реплей), а runId ставим сразу, чтобы UI показал
          // занятость без мигания до прихода RUN_STARTED из потока. Сбой этого запроса не
          // роняет загрузку, но и «свободным» чат тогда не рисуется: занятость остаётся
          // неизвестной, и её переспросит поток сразу после подписки (см. useChatEventStream).
          const [meta, page, run] = await Promise.all([
            chatApi.getChatMeta(chatId),
            chatApi.getMessages(chatId, PAGE_SIZE),
            fetchRunState(chatId).catch(() => null),
          ]);
          const { bubbles, leadingMetas } = transformPage(page.messages);
          const runState = run ? run.state : { ...IDLE_RUN_STATE, runStateUnknown: true };
          // Обрезаем хвост прогона, только если реплей его вернёт. У прогона, чьи события
          // хаб уже начал вытеснять (replayTruncated), реплей начинается не с RUN_STARTED, а
          // с середины — срезанные сегменты не прислал бы никто, и ответ читался бы с
          // полуслова до самого конца прогона.
          const messages = run?.state.runId && !run.replayTruncated ? trimActiveRunTail(bubbles) : bubbles;

          failedChatIdsRef.current.delete(chatId);
          setChats((prev) =>
            prev.map((chat) =>
              chat.id === chatId
                ? {
                    ...chat,
                    messages,
                    ...runState,
                    hasMore: !!page.hasMore,
                    oldestCursor: page.oldestCursor || null,
                    // metas, чей ассистент в ещё не загруженной более старой странице
                    pendingLeadingMetas: leadingMetas,
                    notFound: false,
                    loadError: null,
                    model: meta.model ?? null,
                    // Метаданные для вкладки «Инфо». Из списка чатов они приходят
                    // не всегда: чат, открытый прямой ссылкой, попадает в список
                    // заглушкой без дат.
                    createdAt: meta.createdAt ?? chat.createdAt ?? null,
                    updatedAt: meta.updatedAt ?? chat.updatedAt ?? null,
                    aiTopic: meta.aiTopic ?? chat.aiTopic ?? null,
                  }
                : chat,
            ),
          );
          return bubbles;
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
          onLoadErrorRef.current?.({ notFound: isNotFound, status });
          return undefined;
        } finally {
          loadingMessagesRef.current.delete(chatId);
          markLoading(chatId, false);
        }
      };
      const loading = load();
      loadingMessagesRef.current.set(chatId, loading);
      return loading;
    },
    [setChats, markLoading],
  );

  // Догрузка более старой страницы сообщений (вызывается при прокрутке вверх).
  // Возвращает true, если что-то догрузилось (нужно MessageList для коррекции скролла).
  const loadOlderMessages = useCallback(
    async (chatId) => {
      const chat = getChats().find((c) => c.id === chatId);
      if (!chat || !chat.hasMore || !chat.oldestCursor) return false;
      if (loadingOlderRef.current.has(chatId)) return false;
      loadingOlderRef.current.add(chatId);
      try {
        let page = await chatApi.getMessages(chatId, PAGE_SIZE, chat.oldestCursor);
        let { bubbles: olderBubbles, leadingMetas } = transformPage(page.messages);
        // Страница может целиком состоять из протокольных строк (TOOL-ответы, пустые
        // tool_calls-сегменты) — пузырей из неё не выйдет, но выше история продолжается.
        // Листаем дальше, пока не встретим отображаемое или конец истории.
        while (!olderBubbles.length && !leadingMetas.length && page.hasMore && page.oldestCursor) {
          page = await chatApi.getMessages(chatId, PAGE_SIZE, page.oldestCursor);
          ({ bubbles: olderBubbles, leadingMetas } = transformPage(page.messages));
        }
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
    },
    [getChats, setChats],
  );

  // Триггер: при смене активного чата грузим его сообщения (если ещё не загружены и
  // он не помечен ошибочным) и запоминаем реально существующий чат в localStorage.
  useEffect(() => {
    if (activeChatId && activeChatId !== DRAFT_CHAT_ID) {
      const chat = chats.find((c) => c.id === activeChatId);
      const alreadyFailed = failedChatIdsRef.current.has(activeChatId);
      if (!chat?.messages && !chat?.notFound && !chat?.loadError && !alreadyFailed) {
        loadMessages(activeChatId);
      }
      if (!chat?.notFound && !chat?.loadError && !alreadyFailed) {
        localStorage.setItem(STORAGE_KEY_ACTIVE_CHAT, activeChatId);
      }
    }
  }, [activeChatId, chats, loadMessages]);

  return {
    loadingMessages: loadingChatIds.has(activeChatId),
    loadMessages,
    loadOlderMessages,
    failedChatIdsRef,
  };
}
