// ─── Chat event reducer ──────────────────────────────────────────────────────
// Чистая функция: применяет одно событие из потока /events к объекту чата и
// возвращает новый чат. Один и тот же код обслуживает и собственные сообщения,
// и приходящие от других вкладок — источник правды один (сервер), поэтому рендер
// не зависит от того, какая вкладка инициировала действие.
//
// AI-пузыри активного прогона помечаются runId (транзиентно). По завершении метка
// снимается (finalize). Сообщения из БД runId не имеют — события ложатся поверх
// истории, не конфликтуя с ней.

import { nextMessageId } from './messageId';
import { CHAT_EVENT, FINISH_REASON } from '../../constants/chatEventTypes';
import { SENDER } from '../../constants/messageSender';

// Совпадение вызовов. Когда callIndex известен у обоих — он однозначен (имя +
// порядковый номер в прогоне); иначе фолбэк на name+arguments. Фолбэк нужен для
// живых TOOL_CALL-событий без callIndex, но у него есть предел: два вызова
// одного инструмента с ОДИНАКОВЫМИ аргументами без callIndex сольются в один.
// С callIndex (итоговые TOOL_CALLS-metas) такие вызовы остаются раздельными.
const sameCall = (a, b) => {
  if (a.name !== b.name) return false;
  if (a.callIndex != null && b.callIndex != null) return a.callIndex === b.callIndex;
  return JSON.stringify(a.arguments || {}) === JSON.stringify(b.arguments || {});
};

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
  return [
    ...list,
    {
      name: tc.name,
      arguments: tc.arguments,
      status: tc.status,
      error: tc.error,
      resultGist: tc.resultGist,
      resultMeta: tc.resultMeta,
      callIndex: tc.callIndex,
      hasDetails: tc.hasDetails,
    },
  ];
};

// Индекс последнего AI-пузыря, принадлежащего прогону runId.
const lastAiIndexForRun = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) return i;
  }
  return -1;
};

const pushAi = (msgs, runId) => {
  msgs.push({
    mid: nextMessageId(),
    text: '',
    sender: SENDER.AI,
    runId,
    toolCalls: [],
    timestamp: new Date().toISOString(),
  });
  return msgs.length - 1;
};

// Снимает флаг «модель готовит вызов инструмента» со всех пузырей прогона.
// Вызывается, как только появляется что-то осязаемое: текст, плашка вызова или
// завершение прогона — индикатор «готовлю данные…» при этом исчезает.
const clearPreparing = (msgs, runId) => {
  for (let i = 0; i < msgs.length; i++) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId && msgs[i].preparing) {
      const { preparing: _drop, ...rest } = msgs[i];
      msgs[i] = rest;
    }
  }
};

// Снимает метку runId (для live-tracking) и транзиентный флаг sealed, сохраняет runId
// как toolCallsRunId (для загрузки деталей tool call после завершения прогона).
// Пустые пузыри без вызовов (например, хвостовой после границы сегмента) выбрасывает.
const finalize = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) {
      const { runId: _drop, preparing: _p, sealed: _s, ...rest } = msgs[i];
      const text = (rest.text || '').trimEnd();
      if (text === '' && !(rest.toolCalls || []).length && !rest.error) {
        msgs.splice(i, 1);
      } else {
        msgs[i] = { ...rest, text, toolCallsRunId: runId };
      }
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
    case CHAT_EVENT.USER_MESSAGE: {
      // Своё эхо — уже показано оптимистично.
      if (clientMsgId && ctx.isLocal?.(clientMsgId)) return chat;
      // Дубликат после reload: сообщение уже подгрузилось из БД (хвост истории).
      const last = msgs[msgs.length - 1];
      if (last && last.sender === SENDER.USER && last.text === payload?.text) return chat;
      msgs.push({
        mid: nextMessageId(),
        text: payload?.text || '',
        sender: SENDER.USER,
        timestamp: new Date().toISOString(),
      });
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.RUN_STARTED: {
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
    case CHAT_EVENT.TOOL_PREPARING: {
      return { ...chat, runId };
    }

    case CHAT_EVENT.STREAM: {
      const reason = (payload?.finishReason || '').trim();
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      if (payload?.message) {
        // Пошёл видимый текст — снимаем индикатор подготовки вызова.
        clearPreparing(msgs, runId);
        // Закрытый сегмент не дописываем: текст следующей итерации tool-цикла — новый пузырь.
        if (msgs[idx].sealed) idx = pushAi(msgs, runId);
        // Срезаем ведущие переносы в самом начале ответа.
        const isFirst = msgs[idx].text === '';
        const piece = isFirst ? payload.message.replace(/^\n+/, '') : payload.message;
        if (piece) msgs[idx] = { ...msgs[idx], text: msgs[idx].text + piece };
      }
      // finishReason TOOL_CALLS делит ответ на сегменты: помечаем текущий закрытым (sealed).
      // Новый пузырь НЕ открываем — плашки стартующих инструментов должны прилипнуть к этому
      // сегменту (под текстом, который их вызвал), а следующий пузырь создаст первый текст
      // новой итерации (см. выше).
      if (reason === FINISH_REASON.TOOL_CALLS && !msgs[idx].sealed) {
        clearPreparing(msgs, runId);
        msgs[idx] = { ...msgs[idx], text: msgs[idx].text.trimEnd(), sealed: true };
      }
      return { ...chat, messages: msgs, runId };
    }

    case CHAT_EVENT.TOOL_CALL: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      // Инструмент стартовал — плашка заменяет индикатор подготовки. Само событие — надёжная
      // граница сегмента: раз инструмент пошёл, текст текущей итерации закончен. Полагаться
      // на finishReason=TOOL_CALLS нельзя — агрегированный tool-чанк, который его несёт,
      // ToolCallingAdvisor отфильтровывает из downstream-потока, и STREAM-событие с этим
      // finishReason до фронта не доходит. Печатаем (sealed) сегмент здесь; плашка прилипает
      // к нему — под текстом, который и вызвал инструмент.
      clearPreparing(msgs, runId);
      msgs[idx] = {
        ...msgs[idx],
        text: (msgs[idx].text || '').trimEnd(),
        sealed: true,
        toolCalls: mergeToolCall(msgs[idx].toolCalls || [], payload?.toolCall),
      };
      return { ...chat, messages: msgs, runId };
    }

    case CHAT_EVENT.TOOL_CALLS: {
      // Итоговые metas прогона: раскладываем по сегментам, где уже есть совпавший живой
      // вызов (name+callIndex/arguments); не совпавшие — в последний пузырь прогона.
      const idxLast = lastAiIndexForRun(msgs, runId);
      if (idxLast < 0) return { ...chat, runId };
      clearPreparing(msgs, runId);
      for (const meta of payload?.toolCalls || []) {
        let target = idxLast;
        for (let i = 0; i < msgs.length; i++) {
          const m = msgs[i];
          if (m.sender === SENDER.AI && m.runId === runId && (m.toolCalls || []).some((t) => sameCall(t, meta))) {
            target = i;
            break;
          }
        }
        msgs[target] = { ...msgs[target], toolCalls: mergeToolCall(msgs[target].toolCalls || [], meta) };
      }
      return { ...chat, messages: msgs, runId };
    }

    case CHAT_EVENT.RUN_DONE: {
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    case CHAT_EVENT.RUN_STOPPED: {
      const idx = lastAiIndexForRun(msgs, runId);
      if (idx >= 0) {
        const base = (msgs[idx].text || '').trimEnd();
        msgs[idx] = { ...msgs[idx], text: base ? `${base} ${ctx.stoppedLabel}` : ctx.stoppedLabel };
      }
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    case CHAT_EVENT.RUN_ERROR: {
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
