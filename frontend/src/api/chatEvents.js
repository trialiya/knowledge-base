// ─── Chat event stream ───────────────────────────────────────────────────────
// Постоянное SSE-соединение с GET /api/chats/{id}/events. Через него приходят И
// стриминг текущего ответа, И события от других вкладок (новое сообщение, старт/
// стоп генерации). Читаем через fetch-stream (а не EventSource), чтобы работать с
// тем же механизмом, что и остальной API, и переживать обрывы переподключением с
// дозагрузкой пропущенного (fromSeq = последний виденный seq).

const enc = (id) => encodeURIComponent(id);

// Разбирает один SSE-блок ("id:..\ndata:..") в { id, data }.
const parseBlock = (block) => {
  let data = '';
  let id;
  for (const line of block.split('\n')) {
    const l = line.trimEnd();
    if (l.startsWith('data:')) data += l.slice(5).trim();
    else if (l.startsWith('id:')) id = l.slice(3).trim();
  }
  return { id, data };
};

/**
 * Открывает поток событий чата. Возвращает функцию закрытия.
 *
 * @param {string} chatId
 * @param {object} cb
 * @param {(event:object)=>void} cb.onEvent — на каждое разобранное событие
 * @param {(status:'open'|'reconnecting'|'closed')=>void} [cb.onStatus]
 * @param {()=>void} [cb.onReconnect] — при восстановлении после обрыва (не при первом подключении)
 */
export function openChatEventStream(chatId, { onEvent, onStatus, onReconnect } = {}) {
  let closed = false;
  let controller = null;
  let lastSeq = 0;
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

      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      // SSE-события разделены пустой строкой.
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() || '';
        for (const block of blocks) {
          const { data } = parseBlock(block);
          if (!data) continue;
          try {
            const ev = JSON.parse(data);
            if (typeof ev.seq === 'number') lastSeq = Math.max(lastSeq, ev.seq);
            onEvent?.(ev);
          } catch {
            /* битый кадр — пропускаем */
          }
        }
      }
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
