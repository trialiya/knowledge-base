// ─── Chat event reducer ──────────────────────────────────────────────────────
// Чистая функция: применяет одно событие из потока /events к объекту чата и
// возвращает новый чат. Один и тот же код обслуживает и собственные сообщения,
// и приходящие от других вкладок — источник правды один (сервер), поэтому рендер
// не зависит от того, какая вкладка инициировала действие.
//
// AI-пузыри активного прогона помечаются runId (транзиентно). По завершении метка
// снимается (finalize). Сообщения из БД runId не имеют — события ложатся поверх
// истории, не конфликтуя с ней.
//
// Занятость самого чата — пара runId + runKind (@/constants/runKind): её ставят те же
// два события, что открывают прогон, и снимают терминальные.

import { nextMessageId } from '../messages/messageId';
import {
  sameCall,
  mergeToolCall,
  lastAiIndexForRun,
  pushAi,
  sameProjectSwitch,
  setRunUsage,
  isEventRow,
  isLiveRun,
  applyDelivered,
  finalize,
} from './runMessageOps';
import { CHAT_EVENT, FINISH_REASON } from '@/constants/chatEventTypes';
import { SENDER } from '@/constants/messageSender';
import { RETRY_MODE } from '@/constants/retryMode';
import { RUN_KIND } from '@/constants/runKind';
import { IDLE_RUN_STATE } from './activeRun';

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
      // Своё эхо — уже показано оптимистично; дописать в него осталось только плашку. Ищем
      // именно СВОЙ пузырь, по clientMsgId: последним вопросом в ленте вполне может стоять уже
      // следующий, поставленный в очередь, и плашка уехала бы на него.
      const own = clientMsgId && ctx.isLocal?.(clientMsgId) ? msgs.findIndex((m) => m.clientMsgId === clientMsgId) : -1;
      if (own >= 0) {
        if (sameProjectSwitch(projectSwitch, msgs[own].projectSwitch)) return chat;
        msgs[own] = { ...msgs[own], projectSwitch };
        return { ...chat, messages: msgs };
      }
      // Свой clientMsgId, а пузыря нет: очередь приняли, но ответ на запрос до вкладки не доехал,
      // и обещавший доставку пузырь сняли (см. useChatRun.queueMessage). Значит это не эхо уже
      // показанного, а первая весть о сохранённом сообщении — заводит его общий путь ниже.
      const text = payload?.text || '';
      // id сохранённого сообщения: бэк пишет вопрос до обращения к модели, поэтому он есть
      // уже в событии. Событиям, отреплеенным из прогонов до этого изменения, его взять
      // неоткуда — там остаётся null, и сверка ниже падает обратно на текст.
      const dbId = payload?.id ?? null;
      // Приложенное к вопросу приезжает вместе с ним — чипы появляются и в других
      // вкладках, не дожидаясь перезагрузки.
      const contextItems = payload?.contextItems?.length ? payload.contextItems : null;
      // Дубликат после перезагрузки: наш вопрос уже в истории (подгружен из БД). Ищем
      // ПОСЛЕДНЕЕ USER-сообщение — если оно совпало, это оно и есть, а всё, что идёт после
      // него, — частично сохранённые сегменты текущего (ещё идущего) прогона. Реплей событий
      // пересоберёт этот хвост, поэтому срезаем его: иначе и вопрос, и данные инструментов
      // задвоились бы (reload посреди генерации). Сверять один только последний пузырь мало:
      // после reload за вопросом уже стоят сохранённые ASSISTANT/TOOL-сегменты.
      // (В обычном лайв-потоке своё эхо гасится выше по clientMsgId; сюда попадают лишь
      // реплей после reload и эхо чужих вкладок.)
      for (let i = msgs.length - 1; i >= 0; i--) {
        // Ряд git-команды — тоже USER, но не ход разговора: он лишь отмечает, что репозиторий
        // сдвинули. Останавливаться на нём нельзя ни здесь, ни в поиске своего эха выше — иначе
        // «вопрос → неудачный прогон → git-команда → Повторить» сверялся бы с карточкой git,
        // не находил совпадения и приписывал бы второй такой же вопрос, оставив пузырь с
        // прошлой ошибкой висеть между ними.
        if (isEventRow(msgs[i])) continue;
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
            const kept = msgs.filter((m, j) => j <= i || isEventRow(m));
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
      // Якорь таймера над полем ввода — момент, когда ЭТА вкладка узнала о прогоне (по своим
      // часам: серверное время сюда не мешаем, перекос часов сдвинул бы отсчёт). Реплей после
      // переподключения приносит RUN_STARTED заново — уже поставленный якорь того же прогона
      // не переставляем, иначе таймер прыгнул бы на ноль.
      const runStartedAt = chat.runId === runId && chat.runStartedAt ? chat.runStartedAt : Date.now();
      // Идемпотентно: если пузырь прогона уже есть (оптимистично/из replay) — не дублируем,
      // но модель дописываем: оптимистичный пузырь заводится до ответа сервера и её не знает.
      const i = lastAiIndexForRun(msgs, runId);
      if (i >= 0) {
        if (payload?.model && !msgs[i].model) {
          msgs[i] = { ...msgs[i], model: payload.model };
          return { ...chat, messages: msgs, runId, runKind: RUN_KIND.GENERATION, runStartedAt };
        }
        return { ...chat, runId, runKind: RUN_KIND.GENERATION, runStartedAt };
      }
      pushAi(msgs, runId, payload?.model ?? null);
      return { ...chat, messages: msgs, runId, runKind: RUN_KIND.GENERATION, runStartedAt };
    }

    case CHAT_EVENT.STREAM: {
      if (!isLiveRun(chat, runId)) return chat;
      const reason = (payload?.finishReason || '').trim();
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      if (payload?.message) {
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
        msgs[idx] = { ...msgs[idx], text: msgs[idx].text.trimEnd(), sealed: true };
      }
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.TOOL_CALL: {
      if (!isLiveRun(chat, runId)) return chat;
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      // Само событие — надёжная граница сегмента: раз инструмент пошёл, текст текущей
      // итерации закончен. Полагаться
      // на finishReason=TOOL_CALLS нельзя — агрегированный tool-чанк, который его несёт,
      // ToolCallingAdvisor отфильтровывает из downstream-потока, и STREAM-событие с этим
      // finishReason до фронта не доходит. Печатаем (sealed) сегмент здесь; плашка прилипает
      // к нему — под текстом, который и вызвал инструмент.
      msgs[idx] = {
        ...msgs[idx],
        text: (msgs[idx].text || '').trimEnd(),
        sealed: true,
        toolCalls: mergeToolCall(msgs[idx].toolCalls || [], payload?.toolCall),
      };
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.TOOL_CALLS: {
      if (!isLiveRun(chat, runId)) return chat;
      // Итоговые metas прогона: раскладываем по сегментам, где уже есть совпавший живой
      // вызов (см. sameCall); не совпавшие — в последний пузырь прогона.
      const idxLast = lastAiIndexForRun(msgs, runId);
      if (idxLast < 0) return chat;
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
      return { ...chat, messages: msgs };
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
      return { ...chat, messages: msgs, ...IDLE_RUN_STATE };
    }

    case CHAT_EVENT.RUN_STOPPED: {
      const idx = lastAiIndexForRun(msgs, runId);
      if (idx >= 0) {
        const base = (msgs[idx].text || '').trimEnd();
        msgs[idx] = { ...msgs[idx], text: base ? `${base} ${ctx.stoppedLabel}` : ctx.stoppedLabel };
      }
      finalize(msgs, runId);
      return { ...chat, messages: msgs, ...IDLE_RUN_STATE };
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
      return { ...chat, messages: msgs, ...IDLE_RUN_STATE };
    }

    // ─── Сжатие контекста (/compact) ─────────────────────────────────────────
    // Прогона здесь нет — ни стриминга, ни ответа ассистента, — но чат занят так же,
    // и плашка занятости живёт на том же runId. Один пузырь на всю операцию: он
    // заводится «сжимаю…», а терминальное событие переписывает его текст.
    case CHAT_EVENT.COMPACT_STARTED: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      msgs[idx] = { ...msgs[idx], text: ctx.compactingLabel };
      // Якорь таймера — тем же правилом, что и у RUN_STARTED (реплей его не переставляет).
      // Сжатие идёт по всему контексту сразу и живёт дольше среднего ответа, так что
      // «сколько уже» здесь нужнее всего: без отсчёта плашка «сжимаю…» неотличима от зависшей.
      const runStartedAt = chat.runId === runId && chat.runStartedAt ? chat.runStartedAt : Date.now();
      return { ...chat, messages: msgs, runId, runKind: RUN_KIND.OPERATION, runStartedAt };
    }

    // Плашка «сжимаю…» становится плашкой итога — той же самой, что приезжает из истории
    // после перезагрузки (см. messagesPage.transformPage): один компонент, один вид.
    // dbId — id строки-плашки в БД: по нему модалка запрашивает текст сводки.
    case CHAT_EVENT.COMPACT_DONE: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      msgs[idx] = {
        ...msgs[idx],
        text: '',
        dbId: payload?.messageId ?? null,
        compact: {
          messages: payload?.messages ?? 0,
          summaryChars: payload?.summaryChars ?? 0,
          kind: payload?.kind,
          // Деньги отложенных сводок, которые это сжатие выбросило: своего ряда у них не
          // осталось, и в итог по чату они идут отсюда (см. chatUsageTotals). Из истории
          // плашка приезжает с ними (transformPage) — без них живая вкладка показывала бы
          // итог меньше, чем она же после перезагрузки.
          ...(payload?.carried ? { carried: payload.carried } : {}),
        },
        // Токены раунда — те же, что приедут из истории после перезагрузки: без них счётчик
        // контекста и итоги чата в живой вкладке разошлись бы с перезагруженной.
        ...(payload?.usage ? { usage: payload.usage } : {}),
        ...(payload?.createdAt ? { timestamp: payload.createdAt } : {}),
      };
      finalize(msgs, runId);
      return { ...chat, messages: msgs, ...IDLE_RUN_STATE };
    }

    // Сжатие, которого не просил пользователь: применённая фоновая сводка или авто-compact у
    // предела контекста. Пузыря у него нет и быть не может — плашка встаёт в СЕРЕДИНУ ленты, там,
    // где кончается свёрнутое. Место ищем по времени плашки — тем же порядком, каким ленту отдаёт
    // история.
    case CHAT_EVENT.COMPACT_APPLIED: {
      if (payload?.messageId == null) return chat;
      if (msgs.some((m) => m.dbId === payload.messageId)) return chat;
      const at = payload.createdAt ? Date.parse(payload.createdAt) : NaN;
      // Якоря нет — лента прокручена мимо этого места и подгрузит плашку сама.
      if (isNaN(at)) return chat;
      const before = msgs.findIndex((m) => {
        const stamp = m.timestamp ? Date.parse(m.timestamp) : NaN;
        return !isNaN(stamp) && stamp > at;
      });
      // Место плашки — за последним свёрнутым рядом, а свёрнутые ряды в ленте остаются: если
      // ничего старше плашки не загружено, свёрнутое лежит в ещё не подгруженной странице, и
      // плашка вместе с ним. Вставить её тут значит поставить вторую такую же: догрузка старых
      // страниц дописывает их в начало как есть, не сверяясь с тем, что уже показано.
      if (before === 0 || !msgs.length) return chat;
      const notice = {
        mid: nextMessageId(),
        sender: SENDER.AI,
        text: '',
        dbId: payload.messageId,
        compact: {
          messages: payload.messages ?? 0,
          summaryChars: payload.summaryChars ?? 0,
          kind: payload.kind,
          ...(payload.carried ? { carried: payload.carried } : {}),
        },
        ...(payload.usage ? { usage: payload.usage } : {}),
        timestamp: payload.createdAt,
      };
      msgs.splice(before < 0 ? msgs.length : before, 0, notice);
      return { ...chat, messages: msgs };
    }

    case CHAT_EVENT.COMPACT_ERROR: {
      let idx = lastAiIndexForRun(msgs, runId);
      if (idx < 0) idx = pushAi(msgs, runId);
      // Без retryMode: повтор здесь — это заново набранная команда, а не тот же ход
      // поверх той же истории (история могла и успеть измениться).
      msgs[idx] = { ...msgs[idx], text: ctx.compactErrorLabel, error: true };
      // Раунд, который сводки не дал, провайдер всё равно посчитал, и бэкенд записал его замер
      // на строку самой команды (см. CompactService.spentRound). Ставим его и здесь: иначе итог
      // по чату в этой вкладке расходился бы с тем, что она увидит после перезагрузки.
      if (payload?.usage && payload?.messageId != null) {
        const command = msgs.findIndex((m) => m.dbId === payload.messageId);
        if (command >= 0) msgs[command] = { ...msgs[command], usage: payload.usage };
      }
      finalize(msgs, runId);
      return { ...chat, messages: msgs, ...IDLE_RUN_STATE };
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

    // ─── Откат файловых правок ответа ───────────────────────────────────────
    // Тот же случай, что и git-команда: ход человека, ряд в конец ленты, дубль
    // по dbId отбрасывается (вкладка, которая откат запустила, получает своё же
    // событие обратно).
    case CHAT_EVENT.FILE_REVERT: {
      const id = payload?.id ?? null;
      if (id != null && msgs.some((m) => m.dbId === id)) return chat;
      msgs.push({
        mid: nextMessageId(),
        dbId: id,
        sender: SENDER.USER,
        fileRevert: payload?.event,
        timestamp: payload?.createdAt ?? null,
      });
      return { ...chat, messages: msgs };
    }

    default:
      return chat;
  }
}
