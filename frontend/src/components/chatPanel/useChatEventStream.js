import { useEffect, useRef } from 'react';
import { openChatEventStream } from '../../api/chatEvents';
import { applyChatEvent } from './chatEventReducer';
import chatApi from '../../api/chatApi';
import { DRAFT_CHAT_ID } from '../../constants/storage';
import { CHAT_EVENT } from '../../constants/chatEventTypes';
import { TOOL_STATUS } from '../../constants/toolStatus';
import { getDocChangeRef, getFileChangeRef } from './toolMeta';

/**
 * Подписка на поток событий активного чата: стриминг ответа + синхронизация между
 * вкладками. Подключаемся ТОЛЬКО когда история уже загружена (messages — массив),
 * чтобы события легли поверх неё, а не были затёрты последующей загрузкой из БД.
 * При обрыве поток сам переподключается и дозагружает пропущенное (см. chatEvents).
 *
 * Чистые ref-ы/сеттеры (chatsRef, localClientIdsRef, tRef, setChats) стабильны и
 * не входят в зависимости эффекта — пересоздавать подписку на каждый чанк нельзя.
 *
 * @param {object}   p
 * @param {string}   p.activeChatId
 * @param {boolean}  p.activeMessagesReady  загружена ли история активного чата
 * @param {object}   p.chatsRef             ref-зеркало списка чатов
 * @param {object}   p.localClientIdsRef    ref: clientMsgId-ы своих сообщений (гасим эхо)
 * @param {object}   p.tRef                 ref на функцию перевода t
 * @param {Function} p.setChats
 * @param {Function} p.onChatDeleted        (chatId) => void — внешнее удаление чата
 * @param {Function} p.onRunSettled         (chatId) => void — RUN_DONE/STOPPED/ERROR
 * @param {Function} p.reloadMessages       (chatId) => void — перезагрузка истории
 * @param {Function} [p.onDocChanged]       (refs) => void — успешные doc-мутации инструментов
 *                                           (createDocument/updateDocument/...) из ОДНОГО TOOL_CALLS
 *                                           события, refs — непустой массив из getDocChangeRef.
 *                                           Один вызов на событие (а не один на tool call): несколько
 *                                           setState подряд в одном тике React 18 схлопнёт до
 *                                           последнего, так что раздельные вызовы потеряли бы все
 *                                           мутации прогона, кроме последней, — например, при
 *                                           создании нескольких документов в одном ответе ассистента.
 * @param {Function} [p.onFileChanged]      (refs) => void — то же для file-мутаций (createFile/editFile),
 *                                           refs из getFileChangeRef
 */
export default function useChatEventStream({
  activeChatId,
  activeMessagesReady,
  chatsRef,
  localClientIdsRef,
  tRef,
  setChats,
  onChatDeleted,
  onRunSettled,
  reloadMessages,
  onDocChanged,
  onFileChanged,
}) {
  // Курсор последнего виденного seq по КАЖДОМУ чату. Живёт всё время, пока смонтирован
  // компонент (переживает переключения чатов), но не переживает перезагрузку страницы —
  // то есть ровно тогда, когда нужно продолжить, а не реплеить заново. Без него каждое
  // возвращение в чат открывало бы поток с fromSeq=0, хаб реплеил бы весь текущий прогон
  // с начала, а редьюсер дописал бы этот реплей поверх уже собранного пузыря — ответ
  // задваивался бы (и выглядел бы как «данные другого чата», когда вопрос в чатах похож).
  const seqByChatRef = useRef(new Map());

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
      fromSeq: seqByChatRef.current.get(chatId) || 0,
      onSeq: (seq) => seqByChatRef.current.set(chatId, seq),
      onEvent: (ev) => {
        if (ev.type === 'CHAT_DELETED') {
          seqByChatRef.current.delete(chatId);
          onChatDeleted(chatId);
          return;
        }
        setChats((prev) => prev.map((c) => (c.id === chatId ? applyChatEvent(c, ev, ctx) : c)));
        // Итоговые metas прогона: resultMeta появляется только здесь (см. toolMeta.js), не в
        // живом TOOL_CALL — поэтому детектируем doc/file-мутации на этом событии.
        if (ev.type === CHAT_EVENT.TOOL_CALLS && (onDocChanged || onFileChanged)) {
          const docRefs = [];
          const fileRefs = [];
          for (const tc of ev.payload?.toolCalls || []) {
            const docRef = getDocChangeRef(tc);
            if (docRef && docRef.status !== TOOL_STATUS.ERROR) docRefs.push(docRef);
            const fileRef = getFileChangeRef(tc);
            if (fileRef && fileRef.status !== TOOL_STATUS.ERROR) fileRefs.push(fileRef);
          }
          // Один вызов колбэка со ВСЕМ списком, а не по одному на tool call — см. JSDoc выше.
          if (docRefs.length > 0) onDocChanged?.(docRefs);
          if (fileRefs.length > 0) onFileChanged?.(fileRefs);
        }
        if (ev.type === 'RUN_DONE' || ev.type === 'RUN_STOPPED' || ev.type === 'RUN_ERROR') {
          // Прогон завершён: хаб очистит свой лог, а следующий прогон в этом чате начнёт
          // seq заново (в т.ч. с нового хаба после выгрузки простаивающего). Сбрасываем
          // курсор, чтобы переподписка снова сделала полный реплей нового прогона.
          seqByChatRef.current.delete(chatId);
          onRunSettled(chatId);
        }
      },
      onReconnect: () => {
        // Соединение восстановилось. Что-то делаем только если UI думает, что идёт прогон.
        const cur = chatsRef.current.find((c) => c.id === chatId);
        if (!cur?.runId) return;
        // Жив ли прогон на самом деле? Если ДА — переподключившийся поток сам догонит
        // пропущенное (fromSeq = курсор чата) и допишет в уже собранный пузырь; трогать
        // историю нельзя, иначе перезагрузка из БД обрезала бы начало ответа. Если НЕТ
        // (бэк перезапустился / прогон завершился, пока рвалось соединение) — показываем
        // ответ из БД и разблокируем ввод.
        chatApi
          .getActiveRun(chatId)
          .then((r) => {
            if (r?.runId) return; // прогон жив — поток продолжает сам
            seqByChatRef.current.delete(chatId);
            setChats((prev) => prev.map((c) => (c.id === chatId ? { ...c, runId: null } : c)));
            reloadMessages(chatId);
          })
          .catch(() => {});
      },
    });
  }, [activeChatId, activeMessagesReady, onRunSettled, onChatDeleted, reloadMessages]); // eslint-disable-line react-hooks/exhaustive-deps
}
