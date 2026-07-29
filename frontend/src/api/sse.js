// ─── Server-Sent Events ─────────────────────────────────────────────────────
// Разбор SSE поверх fetch-потока. Через fetch, а не EventSource, по двум
// причинам: EventSource умеет только GET без тела (импорту нужен POST с
// выборкой) и живёт своей жизнью с переподключениями, которых длинной задаче
// как раз не надо — повторное подключение запустило бы её заново.
//
// Здесь только разбор кадров и чтение тела. Что делать с обрывом, решает
// вызывающий: чат переподключается с дозагрузкой (chatEvents.js), задачи
// администрирования — нет (useJobStream.js).

/**
 * Разбирает один SSE-блок ("id:..\ndata:..") в { id, data }.
 * Несколько строк data: склеиваются через \n, как требует спека SSE.
 */
export const parseBlock = (block) => {
  let id;
  const data = [];
  for (const line of block.split('\n')) {
    const l = line.endsWith('\r') ? line.slice(0, -1) : line;
    if (l.startsWith('data:')) data.push(l.slice(5).replace(/^ /, ''));
    else if (l.startsWith('id:')) id = l.slice(3).trim();
  }
  return { id, data: data.join('\n') };
};

/**
 * Читает тело SSE-ответа до конца, вызывая onData на каждый кадр.
 * События разделены пустой строкой; хвост буфера остаётся до следующего чанка.
 *
 * @param {Response} res ответ fetch с ok=true и телом-потоком
 * @param {(data:string, id?:string)=>void} onData
 */
export async function readSseStream(res, onData) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split('\n\n');
    buffer = blocks.pop() || '';
    for (const block of blocks) {
      const { id, data } = parseBlock(block);
      if (data) onData(data, id);
    }
  }
}

/**
 * Читает SSE-поток как последовательность JSON-событий. Битый кадр
 * пропускается: он не должен ронять остаток потока.
 *
 * @param {Response} res
 * @param {(event:object)=>void} onEvent
 */
export async function readJsonSseStream(res, onEvent) {
  await readSseStream(res, (data) => {
    try {
      onEvent(JSON.parse(data));
    } catch {
      /* битый кадр — пропускаем */
    }
  });
}
