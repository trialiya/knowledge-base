import { useCallback, useEffect, useRef, useState } from 'react';
import chatApi from '../../api/chatApi';
import { STORAGE_KEY_ACTIVE_CHAT, DRAFT_CHAT_ID } from '../../constants/storage';
import { CHAT_PAGE_SIZE as PAGE_SIZE } from '../../constants/pagination';
import { nextMessageId } from './messageId';
import { SENDER } from '../../constants/messageSender';

const metaToCall = (x) => ({
  name: x.name,
  arguments: x.arguments,
  status: x.status,
  error: x.error,
  resultMeta: x.resultMeta,
  resultGist: x.resultGist,
  callIndex: x.callIndex,
  hasDetails: x.hasDetails,
});

// Extracts runId from a system message that carries tool call breadcrumbs.
const extractRunId = (m) => m.runId || null;

// Превращает «сырые» сообщения с бэка (хронологический порядок) в пузыри для рендера.
// Системные сообщения-«крошки» (toolInvocationMetas из ChatMemoryService.saveToolCalls)
// пузырём не показываем, а прикрепляем к предыдущему ответу ассистента — это даёт
// resultMeta для блока «изменения документа».
// Если крошка идёт в самом начале страницы (её ассистент остался в более старой,
// ещё не загруженной странице) — её metas возвращаются в leadingMetas, чтобы прицепить
// их позже, когда догрузим страницу с этим ассистентом (см. attachLeadingMetas).
export const transformPage = (rawMsgs) => {
  const bubbles = [];
  const leadingMetas = [];
  let sawAi = false;
  for (const m of rawMsgs || []) {
    const type = m.type?.toLowerCase?.();
    // Legacy: сообщения-«крошки» вызовов инструментов помечены флагом toolCalls (единый JSON
    // со всеми вызовами прогона в конце). Новые чаты крошек не пишут — их вызовы приходят в
    // toolInvocationMetas обычных assistant-сегментов (см. ниже), но старые чаты живут вечно.
    if (m.toolCalls) {
      const metas = m.toolInvocationMetas;
      const runId = extractRunId(m);
      const prev = bubbles[bubbles.length - 1];
      if (Array.isArray(metas) && metas.length) {
        if (sawAi && prev?.sender === 'ai') {
          prev.toolCalls = [...(prev.toolCalls || []), ...metas.map(metaToCall)];
          if (runId) prev.toolCallsRunId = runId;
        } else {
          // Ассистент этой крошки — в более старой странице: несём metas наверх.
          leadingMetas.push(...metas.map(metaToCall));
        }
      }
      continue; // преамбулу как сообщение не рендерим
    }
    if (type === 'system') continue; // прочие системные сообщения (напр. summary) не показываем
    // Протокольные TOOL-сообщения (ответы инструментов) — не для показа: их содержимое
    // видно через плашки/модалку деталей соответствующего сегмента.
    if (type === 'tool') continue;
    const metas = Array.isArray(m.toolInvocationMetas) ? m.toolInvocationMetas : [];
    // Сегмент из одних tool_calls без текста и без сохранённых metas показывать нечем.
    if (type !== 'user' && !(m.content || '').trim() && !metas.length) continue;
    if (type !== 'user') sawAi = true;
    bubbles.push({
      mid: nextMessageId(),
      // id сообщения в БД — якорь для поиска по чату (find-бар, Ctrl+F): позволяет
      // сопоставить хит бэкенда с пузырём и понять, догружена ли страница с совпадением.
      dbId: m.id ?? null,
      text: m.content,
      sender: type === 'user' ? SENDER.USER : SENDER.AI,
      timestamp: m.timestamp || null,
      // Вызовы инструментов этого сегмента (раздельное сохранение): плашки под пузырём.
      ...(metas.length && type !== 'user'
        ? { toolCalls: metas.map(metaToCall), ...(m.runId ? { toolCallsRunId: m.runId } : {}) }
        : {}),
    });
  }
  return { bubbles, leadingMetas };
};

// Прицепляет «висячие» metas (крошки без ассистента в своей странице) к последнему
// AI-пузырю переданного набора. Возвращает остаток, который не удалось прицепить
// (если в наборе вообще нет ассистента) — его несём дальше вверх.
export const attachLeadingMetas = (bubbles, metas) => {
  if (!metas || !metas.length) return [];
  for (let i = bubbles.length - 1; i >= 0; i--) {
    if (bubbles[i].sender === SENDER.AI) {
      bubbles[i] = { ...bubbles[i], toolCalls: [...(bubbles[i].toolCalls || []), ...metas] };
      return [];
    }
  }
  return metas;
};

/**
 * Загрузка и пагинация сообщений активного чата. Владеет своими защитными ref-ами
 * (повторные/параллельные загрузки) и состоянием loadingMessages; сами сообщения
 * пишет в общий стейт чатов через переданный setChats.
 *
 * @param {object}   p
 * @param {Array}    p.chats          текущий список чатов (для триггер-эффекта)
 * @param {object}   p.chatsRef       ref-зеркало chats (для синхронного чтения)
 * @param {Function} p.setChats       сеттер общего стейта чатов
 * @param {string}   p.activeChatId   id активного чата
 * @param {Function} p.onLoadError    ({ notFound, status }) => void — показать модалку
 * @returns {{ loadingMessages: boolean,
 *             loadMessages: (id:string)=>Promise<void>,
 *             loadOlderMessages: (id:string)=>Promise<boolean>,
 *             failedChatIdsRef: object }}
 */
export default function useChatMessages({ chats, chatsRef, setChats, activeChatId, onLoadError }) {
  const [loadingMessages, setLoadingMessages] = useState(false);

  // Ref для защиты от повторных попыток по chatId, которых нет в списке chats.
  const failedChatIdsRef = useRef(new Set());
  // Защита от параллельных догрузок старых сообщений для одного и того же чата.
  const loadingOlderRef = useRef(new Set());
  // Защита от параллельных начальных загрузок сообщений одного чата.
  // Без неё при старте страницы loadMessages вызывается дважды: первый раз когда
  // chats=[] (до загрузки списка), второй — когда setChats(chatList) меняет стейт.
  const loadingMessagesRef = useRef(new Set());

  // onLoadError может меняться между рендерами — держим в ref, чтобы loadMessages
  // оставался стабильным (его кладут в deps других эффектов/колбэков).
  const onLoadErrorRef = useRef(onLoadError);
  useEffect(() => {
    onLoadErrorRef.current = onLoadError;
  }, [onLoadError]);

  // Загрузка сообщений: последняя страница (PAGE_SIZE) + метаданные чата.
  // Метаданные (model/topic) берём отдельным лёгким запросом includeMessages=false,
  // сами сообщения — пагинированным /messages. Это не тащит весь длинный чат.
  const loadMessages = useCallback(
    async (chatId) => {
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
        onLoadErrorRef.current?.({ notFound: isNotFound, status });
      } finally {
        loadingMessagesRef.current.delete(chatId);
        setLoadingMessages(false);
      }
    },
    [setChats],
  );

  // Догрузка более старой страницы сообщений (вызывается при прокрутке вверх).
  // Возвращает true, если что-то догрузилось (нужно MessageList для коррекции скролла).
  const loadOlderMessages = useCallback(
    async (chatId) => {
      const chat = chatsRef.current.find((c) => c.id === chatId);
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
    [chatsRef, setChats],
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

  return { loadingMessages, loadMessages, loadOlderMessages, failedChatIdsRef };
}
