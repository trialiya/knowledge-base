// ─── Chat event reducer ──────────────────────────────────────────────────────
// Чистая функция: применяет одно событие из потока /events к объекту чата и
// возвращает новый чат. Один и тот же код обслуживает и собственные сообщения,
// и приходящие от других вкладок — источник правды один (сервер), поэтому рендер
// не зависит от того, какая вкладка инициировала действие.
//
// AI-пузыри активного прогона помечаются runId (транзиентно). По завершении метка
// снимается (finalize). Сообщения из БД runId не имеют — события ложатся поверх
// истории, не конфликтуя с ней.

const sameCall = (a, b) => a.name === b.name && JSON.stringify(a.arguments || {}) === JSON.stringify(b.arguments || {});

// Слияние одного вызова инструмента в список (по name+arguments).
// resultGist приходит из живых TOOL_CALL, resultMeta — из итогового TOOL_CALLS;
// сохраняем оба, не затирая уже известное.
const mergeToolCall = (list, tc) => {
  if (!tc) return list;
  const i = list.findIndex((t) => sameCall(t, tc));
  if (i >= 0) {
    return list.map((t, j) =>
      j === i
        ? {
            ...t,
            status: tc.status,
            error: tc.error,
            resultGist: tc.resultGist ?? t.resultGist,
            resultMeta: tc.resultMeta ?? t.resultMeta,
            callIndex: tc.callIndex ?? t.callIndex,
            hasDetails: tc.hasDetails ?? t.hasDetails,
          }
        : t,
    );
  }
  return [...list, { name: tc.name, arguments: tc.arguments, status: tc.status, error: tc.error,
      resultGist: tc.resultGist, resultMeta: tc.resultMeta, callIndex: tc.callIndex, hasDetails: tc.hasDetails }];
};

const mergeToolCalls = (list, metas) => (metas || []).reduce((acc, tc) => mergeToolCall(acc, tc), list);

// Индекс последнего AI-пузыря, принадлежащего прогону runId.
const lastAiIndexForRun = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === 'ai' && msgs[i].runId === runId) return i;
  }
  return -1;
};

const pushAi = (msgs, runId) => {
  msgs.push({ text: '', sender: 'ai', runId, toolCalls: [], timestamp: new Date().toISOString() });
  return msgs.length - 1;
};

// Снимает флаг «модель готовит вызов инструмента» со всех пузырей прогона.
// Вызывается, как только появляется что-то осязаемое: текст, плашка вызова или
// завершение прогона — индикатор «готовлю данные…» при этом исчезает.
const clearPreparing = (msgs, runId) => {
  for (let i = 0; i < msgs.length; i++) {
    if (msgs[i].sender === 'ai' && msgs[i].runId === runId && msgs[i].preparing) {
      const { preparing: _drop, ...rest } = msgs[i];
      msgs[i] = rest;
    }
  }
};

// Снимает метку runId (для live-tracking) и сохраняет её как toolCallsRunId
// (для загрузки деталей tool call после завершения прогона).
const finalize = (msgs, runId) => {
  for (let i = 0; i < msgs.length; i++) {
    if (msgs[i].sender === 'ai' && msgs[i].runId === runId) {
      const { runId: _drop, preparing: _p, ...rest } = msgs[i];
      msgs[i] = { ...rest, text: (rest.text || '').trimEnd(), toolCallsRunId: runId };
    }
  }
};

/**
 * @param chat объект чата ({ id, messages, runId, ... })
 * @param ev   событие { type, runId, clientMsgId, payload, seq }
 * @param ctx  { isLocal(clientMsgId), stoppedLabel, errorLabel, interruptedNote }
 */
export function applyChatEvent(chat, ev, ctx) {
  if (!chat) return chat;
  const msgs = Array.isArray(chat.messages) ? [...chat.messages] : [];
  const { type, runId, clientMsgId, payload } = ev;

  switch (type) {
    case 'USER_MESSAGE': {
      // Своё эхо — уже показано оптимистично.
      if (clientMsgId && ctx.isLocal?.(clientMsgId)) return chat;
      // Дубликат после reload: сообщение уже подгрузилось из БД (хвост истории).
      const last = msgs[msgs.length - 1];
      if (last && last.sender === 'user' && last.text === payload?.text) return chat;
      msgs.push({ text: payload?.text || '', sender: 'user', timestamp: new Date().toISOString() });
      return { ...chat, messages: msgs };
    }

    case 'RUN_STARTED': {
      // Идемпотентно: если пузырь прогона уже есть (оптимистично/из replay) — не дублируем.
      if (lastAiIndexForRun(msgs, runId) >= 0) return { ...chat, runId };
      pushAi(msgs, runId);
      return { ...chat, messages: msgs, runId };
    }

    // TOOL_PREPARING отключён: сигнал приходит вплотную к TOOL_CALL и не даёт раннего
    // предупреждения. Причина — OpenAiChatModel.internalStream буферизует все дельты
    // tool-call через bufferUntil/ChunkMerger и выдаёт один агрегированный чанк уже
    // с полными аргументами; к этому моменту ToolCallingAdvisor тут же запускает
    // инструмент. Раннего сигнала ни через advisor, ни через observation получить нельзя —
    // единственный доступный хук до буферизации — это AsyncStreamResponse.Handler внутри
    // самого клиента openai-java, но корреляция с conversationId там нетривиальна.
    // Альтернатива: детекция тишины на фронте (таймер после последнего STREAM-события).
    // Подробнее: docs/проект/диагностика-tool-preparing-стриминг.md
    // и docs/features/tool-preparing.md
    case 'TOOL_PREPARING': {
      return { ...chat, runId };
    }

    case 'STREAM': {
      const reason = (payload?.finishReason || '').trim();
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      if (payload?.message) {
        // Пошёл видимый текст — снимаем индикатор подготовки вызова.
        clearPreparing(msgs, runId);
        // Срезаем ведущие переносы в самом начале ответа.
        const isFirst = msgs[idx].text === '';
        const piece = isFirst ? payload.message.replace(/^\n+/, '') : payload.message;
        if (piece) msgs[idx] = { ...msgs[idx], text: msgs[idx].text + piece };
      }
      // finishReason TOOL_CALLS делит ответ на сегменты: закрываем текущий, открываем новый.
      if (reason === 'TOOL_CALLS' && msgs[idx].text.trim() !== '') {
        clearPreparing(msgs, runId);
        msgs[idx] = { ...msgs[idx], text: msgs[idx].text.trimEnd() };
        pushAi(msgs, runId);
      }
      return { ...chat, messages: msgs, runId };
    }

    case 'TOOL_CALL': {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      // Инструмент стартовал — плашка заменяет индикатор подготовки.
      clearPreparing(msgs, runId);
      msgs[idx] = { ...msgs[idx], toolCalls: mergeToolCall(msgs[idx].toolCalls || [], payload?.toolCall) };
      return { ...chat, messages: msgs, runId };
    }

    case 'TOOL_CALLS': {
      const idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) return { ...chat, runId };
      clearPreparing(msgs, runId);
      msgs[idx] = { ...msgs[idx], toolCalls: mergeToolCalls(msgs[idx].toolCalls || [], payload?.toolCalls) };
      return { ...chat, messages: msgs, runId };
    }

    case 'RUN_DONE': {
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    case 'RUN_STOPPED': {
      const idx = lastAiIndexForRun(msgs, runId);
      if (idx >= 0) {
        const base = (msgs[idx].text || '').trimEnd();
        msgs[idx] = { ...msgs[idx], text: base ? `${base} ${ctx.stoppedLabel}` : ctx.stoppedLabel };
      }
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    case 'RUN_ERROR': {
      // Помечаем пузырь error:true — под ним покажем кнопку «Повторить» (см. Message.jsx).
      // Если ассистент ещё не появился (ошибка до первого чанка) — заводим пустой,
      // чтобы было к чему прицепить ошибку и повтор.
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      const partial = (msgs[idx].text || '').trimEnd();
      msgs[idx] = {
        ...msgs[idx],
        text: partial ? partial + ctx.interruptedNote : ctx.errorLabel,
        error: true,
      };
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    default:
      return chat;
  }
}
