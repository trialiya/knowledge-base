// ─── Chat event stream ───────────────────────────────────────────────────────
// Постоянное SSE-соединение с GET /api/chats/{id}/events. Через него приходят И
// стриминг текущего ответа, И события от других вкладок (новое сообщение, старт/
// стоп генерации). Читаем через fetch-stream (а не EventSource), чтобы работать с
// тем же механизмом, что и остальной API, и переживать обрывы переподключением с
// дозагрузкой пропущенного (fromSeq = последний виденный seq).
//
// Разбор кадров — общий (api/sse.js); здесь остаётся только то, что специфично
// для чата: переподключение с backoff и курсор seq.

import { CHAT_EVENT } from '@/constants/chatEventTypes';
import { readSseStream } from './sse';

const enc = (id) => encodeURIComponent(id);

/**
 * Открывает поток событий чата. Возвращает функцию закрытия.
 *
 * @param {string} chatId
 * @param {object} cb
 * @param {(event:object)=>void} cb.onEvent — на каждое разобранное событие
 * @param {(status:'open'|'reconnecting'|'closed')=>void} [cb.onStatus]
 * @param {()=>void} [cb.onReconnect] — при восстановлении после обрыва (не при первом подключении)
 * @param {number} [cb.fromSeq] — с какого seq начинать (пропущенное дозагрузится). По умолчанию 0
 *   (полный реплей). Позволяет продолжить с места, на котором остановились в прошлой подписке на
 *   ЭТОТ же чат (переключение чатов), не реплея заново уже применённые события — иначе редьюсер
 *   дописал бы реплей поверх уже собранного пузыря и удвоил бы текст ответа.
 * @param {(seq:number)=>void} [cb.onSeq] — последний виденный seq (чтобы вызывающий запомнил
 *   курсор чата и передал его в fromSeq при переподписке)
 */
export function openChatEventStream(chatId, { onEvent, onStatus, onReconnect, fromSeq = 0, onSeq } = {}) {
  let closed = false;
  let controller = null;
  let lastSeq = fromSeq;
  let attempt = 0;

  const connect = async () => {
    if (closed) return;
    controller = new AbortController();
    try {
      const res = await fetch(`/api/chats/${enc(chatId)}/events?fromSeq=${lastSeq}`, {
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      });
      if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
      const wasReconnecting = attempt > 0;
      attempt = 0;
      onStatus?.('open');
      if (wasReconnecting) onReconnect?.();

      await readSseStream(res, (data) => {
        try {
          const ev = JSON.parse(data);
          if (typeof ev.seq === 'number') {
            // REPLAY_GAP не «ещё одно событие», а ответ хаба про сам курсор: его seq — то
            // значение, с которого реплей продолжится. Присваиваем, а не поднимаем максимум:
            // курсор из прошлой жизни хаба (хаб пережил не всякую вкладку — см.
            // ConversationHub#ownCursor) хаб не принимает, и REPLAY_GAP двигает его ВНИЗ, на
            // начало лога. Максимум оставил бы такой курсор стоять — и каждый обрыв связи
            // приносил бы полный реплей поверх уже собранного пузыря, задваивая ответ.
            lastSeq = ev.type === CHAT_EVENT.REPLAY_GAP ? ev.seq : Math.max(lastSeq, ev.seq);
            onSeq?.(lastSeq);
          }
          onEvent?.(ev);
        } catch {
          /* битый кадр — пропускаем */
        }
      });
    } catch {
      /* сеть/таймаут — упадём в переподключение ниже */
    }
    if (closed) return;
    // Соединение закрылось (таймаут сервера/обрыв) — переподключаемся с backoff,
    // дозагружая пропущенное по lastSeq.
    onStatus?.('reconnecting');
    attempt += 1;
    const delay = Math.min(1000 * 2 ** Math.min(attempt, 4), 16000);
    setTimeout(connect, delay);
  };

  connect();

  return () => {
    closed = true;
    onStatus?.('closed');
    try {
      controller?.abort();
    } catch {
      /* ignore */
    }
  };
}
