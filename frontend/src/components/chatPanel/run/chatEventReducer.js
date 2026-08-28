// ─── Chat event reducer ──────────────────────────────────────────────────────
// Чистая функция: применяет одно событие из потока /events к объекту чата и
// возвращает новый чат. Один и тот же код обслуживает и собственные сообщения,
// и приходящие от других вкладок — источник правды один (сервер), поэтому рендер
// не зависит от того, какая вкладка инициировала действие.
//
// AI-пузыри активного прогона помечаются runId (транзиентно). По завершении метка
// снимается (finalize). Сообщения из БД runId не имеют — события ложатся поверх
// истории, не конфликтуя с ней.

import { nextMessageId } from '../messages/messageId';
import { CHAT_EVENT, FINISH_REASON } from '@/constants/chatEventTypes';
import { SENDER } from '@/constants/messageSender';
import { RETRY_MODE } from '@/constants/retryMode';

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
const lastAiIndexForRun = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) return i;
  }
  return -1;
};

// model — id модели прогона из RUN_STARTED. Помечаем пузырь сразу, а не по завершении:
// подпись под ответом обязана быть той же и в живом потоке, и после перезагрузки, где
// она приезжает из meta.model сохранённого ряда (см. ChatHistoryService.markRunResult).
const pushAi = (msgs, runId, model = null) => {
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
const sameProjectSwitch = (a, b) => (a?.from ?? null) === (b?.from ?? null) && (a?.to ?? null) === (b?.to ?? null);

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

const setRunUsage = (msgs, runId, usage, live) => {
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
const isGitRow = (message) => !!message.gitEvent;

/**
 * Сообщение из очереди доставлено в историю (см. MESSAGE_QUEUED). Общий путь USER_MESSAGE здесь
 * не годится: он опознаёт эхо уже показанного хода и срезает всё, что стоит после него, — а
 * доставка внутрь прогона хода не кончает, и всё выше модель написала до этого вопроса.
 *
 * @param waiting индекс «ожидающего» пузыря (или -1): его завела своя вкладка оптимистично, а
 *     чужие — по MESSAGE_QUEUED, и у всех он помечен тем же clientMsgId
 */
const applyDelivered = (chat, msgs, { runId, payload }, waiting) => {
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
const finalize = (msgs, runId) => {
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].sender === SENDER.AI && msgs[i].runId === runId) {
      const { runId: _drop, preparing: _p, sealed: _s, ...rest } = msgs[i];
      const text = (rest.text || '').trimEnd();
      if (text === '' && !(rest.toolCalls || []).length && !rest.error && !rest.compact) {
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
 * @param ctx  { isLocal(clientMsgId), stoppedLabel, errorLabel, interruptedNote,
 *               compactingLabel, compactErrorLabel }
 */
export function applyChatEvent(chat, ev, ctx) {
  if (!chat) return chat;
  const msgs = Array.isArray(chat.messages) ? [...chat.messages] : [];
  const { type, runId, clientMsgId, payload } = ev;

  switch (type) {
    // Сообщение принято в очередь идущего прогона: ряда истории у него ещё нет, поэтому
    // пузырь заводится «ожидающим» — его увидят и вкладки, которые ничего не отправляли.
    // Своя вкладка показала его оптимистично и второй раз не заводит.
    case CHAT_EVENT.MESSAGE_QUEUED: {
      if (clientMsgId && ctx.isLocal?.(clientMsgId)) return chat;
      if (clientMsgId && msgs.some((m) => m.clientMsgId === clientMsgId)) return chat;
      msgs.push({
        mid: nextMessageId(),
        clientMsgId,
        queued: true,
        text: payload?.text || '',
        sender: SENDER.USER,
        ...(payload?.contextItems?.length ? { contextItems: payload.contextItems } : {}),
        timestamp: payload?.createdAt ?? new Date().toISOString(),
      });
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.USER_MESSAGE: {
      // Доставка сообщения из очереди — по «ожидающему» пузырю или по флагу в событии.
      // Оба признака нужны: пузыря может не быть (вкладка вошла позже), а флага — у
      // доставки после конца прогона.
      const waiting = clientMsgId ? msgs.findIndex((m) => m.queued && m.clientMsgId === clientMsgId) : -1;
      if (waiting >= 0 || payload?.interjection) return applyDelivered(chat, msgs, ev, waiting);
      // Этим вопросом чат сменил проект. Решает это бэкенд (сравнением с сохранённым у чата),
      // поэтому оптимистичный пузырь плашку не знал — она доезжает только эхом.
      const projectSwitch = payload?.projectSwitchFrom
        ? { from: payload.projectSwitchFrom, to: payload.project }
        : null;
      // Своё эхо — уже показано оптимистично; дописать в него осталось только плашку.
      if (clientMsgId && ctx.isLocal?.(clientMsgId)) {
        for (let i = msgs.length - 1; i >= 0; i--) {
          if (msgs[i].sender !== SENDER.USER || isGitRow(msgs[i])) continue;
          if (sameProjectSwitch(projectSwitch, msgs[i].projectSwitch)) return chat;
          msgs[i] = { ...msgs[i], projectSwitch };
          return { ...chat, messages: msgs };
        }
        return chat;
      }
      const text = payload?.text || '';
      // id сохранённого сообщения: бэк пишет вопрос до обращения к модели, поэтому он есть
      // уже в событии. Событиям, отреплеенным из прогонов до этого изменения, его взять
      // неоткуда — там остаётся null, и сверка ниже падает обратно на текст.
      const dbId = payload?.id ?? null;
      // Приложенное к вопросу приезжает вместе с ним — чипы появляются и в других
      // вкладках, не дожидаясь перезагрузки.
      const contextItems = payload?.contextItems?.length ? payload.contextItems : null;
      // Дубликат после перезагрузки: наш вопрос уже в истории (подгружен из БД). Ищем
      // ПОСЛЕДНЕЕ USER-сообщение — если оно совпало по тексту, это оно и есть, а всё,
      // что идёт после него, — частично сохранённые сегменты текущего (ещё идущего)
      // прогона. Реплей событий пересоберёт этот хвост, поэтому срезаем его: иначе и
      // вопрос, и данные инструментов задвоились бы (reload посреди генерации). Раньше
      // сверяли только самый последний пузырь, но после reload за вопросом уже стоят
      // сохранённые ASSISTANT/TOOL-сегменты, и проверка не срабатывала.
      // (В обычном лайв-потоке своё эхо гасится выше по clientMsgId; сюда попадают лишь
      // реплей после reload и эхо чужих вкладок.)
      for (let i = msgs.length - 1; i >= 0; i--) {
        // Ряд git-команды — тоже USER, но не ход разговора: он лишь отмечает, что репозиторий
        // сдвинули. Останавливаться на нём нельзя ни здесь, ни в поиске своего эха выше — иначе
        // «вопрос → неудачный прогон → git-команда → Повторить» сверялся бы с карточкой git,
        // не находил совпадения и приписывал бы второй такой же вопрос, оставив пузырь с
        // прошлой ошибкой висеть между ними.
        if (isGitRow(msgs[i])) continue;
        if (msgs[i].sender === SENDER.USER) {
          // Сверяем по dbId, когда он есть с обеих сторон: два одинаковых вопроса подряд
          // (частый случай — «Повторить») текстовая сверка приняла бы за одно сообщение.
          const sameMessage = dbId != null && msgs[i].dbId != null ? msgs[i].dbId === dbId : msgs[i].text === text;
          if (sameMessage) {
            // Пузырь из истории мог прийти без dbId или без чипов (реплей после
            // reload, эхо своей же отправки) — дополняем тем, чего не хватает.
            const patch = {
              ...(dbId != null && msgs[i].dbId == null ? { dbId } : {}),
              ...(contextItems && !msgs[i].contextItems?.length ? { contextItems } : {}),
              // Плашка, в отличие от остального в этом патче, не «дописывается, если её нет», а
              // берётся из эха как есть: повтор прогона считает смену заново, и уехавший в третий
              // проект вопрос обязан потерять прежнюю плашку, а вернувшийся в исходный — вообще
              // всякую (бэкенд её в этом случае снимает).
              ...(sameProjectSwitch(projectSwitch, msgs[i].projectSwitch) ? {} : { projectSwitch }),
            };
            const patched = Object.keys(patch).length > 0;
            if (patched) msgs[i] = { ...msgs[i], ...patch };
            // Страховка от залипшего «ожидает отправки»: сюда доезжает и эхо follow-up прогона,
            // который отвечает на доставленное сообщение, — своего clientMsgId у него уже нет,
            // и ветка доставки выше по нему не сработала бы. Ряд в истории есть, значит ждать
            // больше нечего, чем бы этот пузырь ни был помечен.
            if (msgs[i].queued) {
              const { queued: _drop, ...rest } = msgs[i];
              msgs[i] = rest;
              return { ...chat, messages: msgs };
            }
            if (i === msgs.length - 1) return patched ? { ...chat, messages: msgs } : chat;
            // Хвост срезаем, но карточки git в нём оставляем — по тому же правилу, что и
            // `trimActiveRunTail`: их пересобирать нечем, реплей событий прогона их не вернёт.
            // Если срезать оказалось нечего (за вопросом стоят одни карточки), чат остаётся
            // прежним объектом: повторное эхо не должно перерисовывать ленту впустую.
            const kept = msgs.filter((m, j) => j <= i || isGitRow(m));
            if (kept.length === msgs.length) return patched ? { ...chat, messages: msgs } : chat;
            return { ...chat, messages: kept };
          }
          break; // последний вопрос не совпал — это действительно новое сообщение
        }
      }
      msgs.push({
        mid: nextMessageId(),
        dbId,
        text,
        sender: SENDER.USER,
        ...(contextItems ? { contextItems } : {}),
        ...(projectSwitch ? { projectSwitch } : {}),
        timestamp: new Date().toISOString(),
      });
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.RUN_STARTED: {
      // Идемпотентно: если пузырь прогона уже есть (оптимистично/из replay) — не дублируем,
      // но модель дописываем: оптимистичный пузырь заводится до ответа сервера и её не знает.
      const i = lastAiIndexForRun(msgs, runId);
      if (i >= 0) {
        if (payload?.model && !msgs[i].model) {
          msgs[i] = { ...msgs[i], model: payload.model };
          return { ...chat, messages: msgs, runId };
        }
        return { ...chat, runId };
      }
      pushAi(msgs, runId, payload?.model ?? null);
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
        // Срезаем ведущие переносы в начале ответа и в начале нового сегмента.
        const startsBubble = msgs[idx].sealed || msgs[idx].text === '';
        const piece = startsBubble ? payload.message.replace(/^\n+/, '') : payload.message;
        // Закрытый сегмент не дописываем: текст следующей итерации tool-цикла — новый пузырь.
        // Но открываем его только под непустой текст: whitespace-чанк между вызовами
        // инструментов не должен порождать пустой пузырь, разрывающий ленту плашек.
        if (piece) {
          // Модель наследуется: новый сегмент — тот же прогон, значит та же модель.
          if (msgs[idx].sealed) idx = pushAi(msgs, runId, msgs[idx].model ?? null);
          msgs[idx] = { ...msgs[idx], text: msgs[idx].text + piece };
        }
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

    case CHAT_EVENT.RUN_USAGE: {
      // Провайдер, не присылающий usage, не шлёт и события — плашки просто не будет. Пустую
      // нагрузку всё же отсеиваем: ноль токенов значил бы «посчитали и вышел ноль».
      if (!payload?.contextTokens) return chat;
      if (!setRunUsage(msgs, runId, payload, chat.runId === runId)) return chat;
      // runId чата не трогаем: замер прогона его не начинает и не кончает, а событие вправе
      // приехать и после RUN_DONE — подставленный отсюда runId воскресил бы законченный прогон.
      return { ...chat, messages: msgs };
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
      // Помечаем пузырь error:true — под ним может появиться кнопка «Повторить»
      // (см. MessageList/Message.jsx). Если ассистент ещё не появился (ошибка до первого
      // чанка) — заводим пустой, чтобы было к чему прицепить ошибку.
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      const partial = (msgs[idx].text || '').trimEnd();
      // Повтор предлагаем, только пока модель ничего не выдала: ни текста, ни вызова
      // инструмента ни в одном сегменте прогона. Тогда вопрос в истории остался
      // неотвеченным и прогон можно просто запустить заново (RETRY_MODE.CONTINUE) —
      // без второго USER-сообщения. Если ответ уже начался, переиграть ход молча
      // нельзя: пришлось бы либо задвоить вопрос, либо стереть сделанное моделью
      // (включая побочные эффекты уже выполненных инструментов). Тот же инвариант
      // проверяет бэк — ChatHistoryService.unansweredUserMessage.
      const produced = msgs.some(
        (m) =>
          m.sender === SENDER.AI && m.runId === runId && ((m.text || '').trim() !== '' || (m.toolCalls || []).length),
      );
      msgs[idx] = {
        ...msgs[idx],
        text: partial ? partial + ctx.interruptedNote : ctx.errorLabel,
        error: true,
        ...(produced ? {} : { retryMode: RETRY_MODE.CONTINUE }),
      };
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null };
    }

    // ─── Сжатие контекста (/compact) ─────────────────────────────────────────
    // Прогона здесь нет — ни стриминга, ни ответа ассистента, — но чат занят так же,
    // и плашка занятости живёт на том же runId. Один пузырь на всю операцию: он
    // заводится «сжимаю…», а терминальное событие переписывает его текст.
    case CHAT_EVENT.COMPACT_STARTED: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      msgs[idx] = { ...msgs[idx], text: ctx.compactingLabel };
      return { ...chat, messages: msgs, runId, compacting: true };
    }

    // Плашка «сжимаю…» становится плашкой итога — той же самой, что приезжает из истории
    // после перезагрузки (см. useChatMessages.transformPage): один компонент, один вид.
    // dbId — id строки-плашки в БД: по нему модалка запрашивает текст сводки.
    case CHAT_EVENT.COMPACT_DONE: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      msgs[idx] = {
        ...msgs[idx],
        text: '',
        dbId: payload?.messageId ?? null,
        compact: { messages: payload?.messages ?? 0, summaryChars: payload?.summaryChars ?? 0 },
        ...(payload?.createdAt ? { timestamp: payload.createdAt } : {}),
      };
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null, compacting: false };
    }

    case CHAT_EVENT.COMPACT_ERROR: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      // Без retryMode: повтор здесь — это заново набранная команда, а не тот же ход
      // поверх той же истории (история могла и успеть измениться).
      msgs[idx] = { ...msgs[idx], text: ctx.compactErrorLabel, error: true };
      finalize(msgs, runId);
      return { ...chat, messages: msgs, runId: null, compacting: false };
    }

    // ─── Git-команда пользователя ────────────────────────────────────────────
    // Прогона нет и здесь: команда — ход человека, а не модели. Ряд дописывается
    // в конец, как приехал бы из истории, и той же карточкой; дубль по dbId
    // отбрасывается — вкладка, которая команду запустила, получает своё же
    // событие обратно, а после переподключения ещё и переиграет пропущенные.
    case CHAT_EVENT.GIT_COMMAND: {
      const id = payload?.id ?? null;
      if (id != null && msgs.some((m) => m.dbId === id)) return chat;
      msgs.push({
        mid: nextMessageId(),
        dbId: id,
        sender: SENDER.USER,
        gitEvent: payload?.event,
        timestamp: payload?.createdAt ?? null,
      });
      return { ...chat, messages: msgs };
    }

    default:
      return chat;
  }
}
