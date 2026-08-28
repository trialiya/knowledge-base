// ─── Операции над лентой сообщений прогона ───────────────────────────────────
// Чистые помощники редьюсера событий (chatEventReducer): опознание вызовов
// инструментов, открытие и закрытие AI-пузырей прогона, доставка сообщения из
// очереди. Массив сообщений они правят на месте — редьюсер копирует его один раз
// на событие и передаёт сюда уже свою копию.

import { nextMessageId } from '../messages/messageId';
import { SENDER } from '@/constants/messageSender';

// Совпадение вызовов. И живое событие TOOL_CALL, и итоговая мета прогона несут протокольный
// callId и сквозной callIndex — по ним вызов опознаётся однозначно. Фолбэк на name+arguments
// остаётся ради записей, сделанных до появления этих полей: у него есть предел — два вызова
// одного инструмента с ОДИНАКОВЫМИ аргументами сливаются в один.
export const sameCall = (a, b) => {
  if (a.callId != null && b.callId != null) return a.callId === b.callId;
  if (a.name !== b.name) return false;
  if (a.callIndex != null && b.callIndex != null) return a.callIndex === b.callIndex;
  return JSON.stringify(a.arguments || {}) === JSON.stringify(b.arguments || {});
};

// Слияние одного вызова инструмента в список (по name+arguments).
// resultGist приходит из живых TOOL_CALL, resultMeta — из итогового TOOL_CALLS;
// сохраняем оба, не затирая уже известное.
export const mergeToolCall = (list, tc) => {
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
            callId: tc.callId ?? t.callId,
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
      callId: tc.callId,
    },
  ];
};

// Индекс последнего AI-пузыря, принадлежащего прогону runId.
export const lastAiIndexForRun = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) return i;
  }
  return -1;
};

// model — id модели прогона из RUN_STARTED. Помечаем пузырь сразу, а не по завершении:
// подпись под ответом обязана быть той же и в живом потоке, и после перезагрузки, где
// она приезжает из meta.model сохранённого ряда (см. ChatHistoryService.markRunResult).
export const pushAi = (msgs, runId, model = null) => {
  const bubble = {
    mid: nextMessageId(),
    text: '',
    sender: SENDER.AI,
    runId,
    toolCalls: [],
    ...(model ? { model } : {}),
    timestamp: new Date().toISOString(),
  };
  // Пузыри, ждущие доставки, стоят в конце ленты и остаются там: ряда истории у них ещё
  // нет, и лягут они после всего, что модель успеет написать за время ожидания. Новый
  // сегмент ответа поэтому встаёт ПЕРЕД ними — иначе живой порядок разошёлся бы с тем,
  // что покажет перезагрузка, и вопрос «прыгал» бы на строку ниже.
  let at = msgs.length;
  while (at > 0 && msgs[at - 1].queued) at--;
  msgs.splice(at, 0, bubble);
  return at;
};

// Одна ли это плашка смены проекта. Сравнение по значению, а не по ссылке: из каждого эха
// приезжает свежий объект, а отсутствие плашки — это и null, и undefined.
export const sameProjectSwitch = (a, b) =>
  (a?.from ?? null) === (b?.from ?? null) && (a?.to ?? null) === (b?.to ?? null);

// Токены прогона — нарастающий ИТОГ, а не добавка сегмента: событие приходит по нескольку раз
// за прогон, и каждое следующее заменяет предыдущее целиком. Держим цифру на последнем пузыре
// прогона и снимаем со всех прежних: у прогона с инструментами сегментов несколько, а потрачено
// на них одно на всех — плашка на каждом читалась бы как несколько разных счетов.
const ofRun = (message, runId) =>
  message.sender === SENDER.AI && (message.runId === runId || message.toolCallsRunId === runId);

// Пузырь прогона: у идущего он помечен runId, у законченного — toolCallsRunId (finalize снимает
// первый и ставит второй). Искать только по runId нельзя: замер вправе приехать после RUN_DONE, и
// тогда пузырь ответа не нашёлся бы, а плашка уехала бы на свежесозданный пустой.
const lastAiIndexOfRun = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (ofRun(msgs[i], runId)) return i;
  }
  return -1;
};

export const setRunUsage = (msgs, runId, usage, live) => {
  let idx = lastAiIndexOfRun(msgs, runId);
  if (idx < 0) {
    // Пузыря нет вовсе: у идущего прогона он ещё не открыт, у законченного — не остался (ответ
    // вышел пустым и finalize его удалил). Открывать пузырь ради плашки во втором случае нечего.
    if (!live) return false;
    idx = pushAi(msgs, runId);
  }
  for (let i = 0; i < msgs.length; i++) {
    if (i !== idx && ofRun(msgs[i], runId) && msgs[i].usage) {
      const { usage: _drop, ...rest } = msgs[i];
      msgs[i] = rest;
    }
  }
  msgs[idx] = { ...msgs[idx], usage };
  return true;
};

/** Карточка выполненной git-команды: отправитель у неё USER, ходом разговора она не является. */
export const isGitRow = (message) => !!message.gitEvent;

/**
 * Тот ли это прогон, что чат считает идущим. Прогон в чате открывают ровно двое — RUN_STARTED и
 * COMPACT_STARTED, — а остальные события прогона в него только пишут, и лишь пока он тот же.
 * Событие, доехавшее после терминального (последний чанк отменённого прогона, запись истории из
 * tool-цикла), иначе воскресило бы законченный прогон: своё терминальное событие он уже отдал,
 * снять прогон стало бы некому, и чат остался бы с кнопкой «остановить» и фантомным пузырём до
 * ухода из него и обратно. Окно узкое, но оно есть у каждого, кто публикует из прогона, а вкладку
 * с открытым чатом такое событие застаёт: хаб держит её подписка, и до неё оно доходит.
 *
 * Исключение ровно одно — RUN_USAGE: замер последнего чанка вправе доехать после конца прогона,
 * и он полезен, поэтому его ветка ищет пузырь и по законченному прогону тоже, но runId чату всё
 * равно не возвращает.
 */
export const isLiveRun = (chat, runId) => chat.runId === runId;

/**
 * Сообщение из очереди доставлено в историю (см. MESSAGE_QUEUED). Общий путь USER_MESSAGE здесь
 * не годится: он опознаёт эхо уже показанного хода и срезает всё, что стоит после него, — а
 * доставка внутрь прогона хода не кончает, и всё выше модель написала до этого вопроса.
 *
 * @param waiting индекс «ожидающего» пузыря (или -1): его завела своя вкладка оптимистично, а
 *     чужие — по MESSAGE_QUEUED, и у всех он помечен тем же clientMsgId
 */
export const applyDelivered = (chat, msgs, { runId, payload }, waiting) => {
  const dbId = payload?.id ?? null;
  const contextItems = payload?.contextItems?.length ? payload.contextItems : null;
  const interjection = !!payload?.interjection;
  if (waiting >= 0) {
    const { queued: _drop, ...rest } = msgs[waiting];
    msgs[waiting] = {
      ...rest,
      dbId,
      ...(interjection ? { interjection: true } : {}),
      ...(contextItems ? { contextItems } : {}),
    };
  } else if (dbId != null && msgs.some((m) => m.dbId === dbId)) {
    return chat; // реплей уже применённого события
  } else {
    msgs.push({
      mid: nextMessageId(),
      dbId,
      text: payload?.text || '',
      sender: SENDER.USER,
      ...(interjection ? { interjection: true } : {}),
      ...(contextItems ? { contextItems } : {}),
      timestamp: payload?.createdAt ?? new Date().toISOString(),
    });
  }
  // Прогон продолжается — закрываем его текущий сегмент, чтобы продолжение ответа встало ПОД
  // вопросом, а не над ним. Новый сегмент открываем сразу, а не флагом sealed: STREAM открыл бы
  // его и сам, а вот TOOL_CALL прилепил бы плашку к сегменту над вопросом.
  const ai = interjection ? lastAiIndexForRun(msgs, runId) : -1;
  if (ai >= 0) {
    const segment = msgs[ai];
    const written = (segment.text || '').trim() !== '' || (segment.toolCalls || []).length > 0;
    // Сегмент, в который ещё ничего не написали, не размножаем, а переносим вниз: очередь
    // доставляется целиком, и на каждое сообщение он открывался бы заново, оставляя в
    // середине ленты пустые плашки до самого finalize.
    if (!written) msgs.splice(ai, 1);
    else msgs[ai] = { ...segment, text: (segment.text || '').trimEnd() };
    pushAi(msgs, runId, segment.model ?? null);
  }
  return { ...chat, messages: msgs };
};

// Снимает метку runId (для live-tracking) и транзиентный флаг sealed, сохраняет runId
// как toolCallsRunId (для загрузки деталей tool call после завершения прогона).
// Пустые пузыри без вызовов (например, хвостовой после границы сегмента) выбрасывает —
// кроме плашки сжатия: у неё текста нет вовсе, весь её смысл в поле compact.
export const finalize = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) {
      const { runId: _drop, sealed: _s, ...rest } = msgs[i];
      const text = (rest.text || '').trimEnd();
      if (text === '' && !(rest.toolCalls || []).length && !rest.error && !rest.compact) {
        msgs.splice(i, 1);
      } else {
        msgs[i] = { ...rest, text, toolCallsRunId: runId };
      }
    }
  }
};
