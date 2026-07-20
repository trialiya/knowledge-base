import { useEffect, useRef } from 'react';
import { openChatEventStream } from '../../api/chatEvents';
import { applyChatEvent } from './chatEventReducer';
import { DRAFT_CHAT_ID } from '../../constants/storage';

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
        if (ev.type === 'RUN_DONE' || ev.type === 'RUN_STOPPED' || ev.type === 'RUN_ERROR') {
          // Прогон завершён: хаб очистит свой лог, а следующий прогон в этом чате начнёт
          // seq заново (в т.ч. с нового хаба после выгрузки простаивающего). Сбрасываем
          // курсор, чтобы переподписка снова сделала полный реплей нового прогона.
          seqByChatRef.current.delete(chatId);
          onRunSettled(chatId);
        }
      },
      onReconnect: () => {
        // Перезагружаем только если UI думает что идёт прогон — в этом случае либо
        // бэк перезапустился (прогон мёртв, нужно показать ответ из БД и разблокировать
        // ввод), либо прогон завершился пока соединение было сломано. При обычном сетевом
        // сбое без потери прогона runId === null и мы ничего лишнего не делаем.
        const cur = chatsRef.current.find((c) => c.id === chatId);
        if (cur?.runId) {
          setChats((prev) => prev.map((c) => (c.id === chatId ? { ...c, runId: null } : c)));
          reloadMessages(chatId);
        }
      },
    });
  }, [activeChatId, activeMessagesReady, onRunSettled, onChatDeleted, reloadMessages]); // eslint-disable-line react-hooks/exhaustive-deps
}
