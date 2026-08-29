// ─── Страница истории → пузыри ленты ─────────────────────────────────────────
// Чистое преобразование сохранённых сообщений в то, что рисует лента: крошки вызовов
// инструментов, плашки сжатия и ряды git-команд, обрезка хвоста идущего прогона и
// «висячие» metas, чей ассистент остался в ещё не догруженной странице.

import { nextMessageId } from '../messages/messageId';
import { SENDER } from '@/constants/messageSender';
import { toolCallOf } from './runMessageOps';

// Extracts runId from a system message that carries tool call breadcrumbs.
const extractRunId = (m) => m.runId || null;

// Превращает «сырые» сообщения с бэка (хронологический порядок) в пузыри для рендера.
// Системные сообщения-«крошки» (toolInvocationMetas из ChatHistoryService.markRunResult)
// пузырём не показываем, а прикрепляем к предыдущему ответу ассистента — это даёт
// resultMeta для блока «изменения документа».
// Если крошка идёт в самом начале страницы (её ассистент остался в более старой,
// ещё не загруженной странице) — её metas возвращаются в leadingMetas, чтобы прицепить
// их позже, когда догрузим страницу с этим ассистентом (см. attachLeadingMetas).
export const transformPage = (rawMsgs) => {
  const bubbles = [];
  const leadingMetas = [];
  let sawAi = false;
  for (const m of rawMsgs || []) {
    const type = m.type?.toLowerCase?.();
    // Legacy: сообщения-«крошки» вызовов инструментов помечены флагом toolCalls (единый JSON
    // со всеми вызовами прогона в конце). Новые чаты крошек не пишут — их вызовы приходят в
    // toolInvocationMetas обычных assistant-сегментов (см. ниже), но старые чаты живут вечно.
    if (m.toolCalls) {
      const metas = m.toolInvocationMetas;
      const runId = extractRunId(m);
      const prev = bubbles[bubbles.length - 1];
      if (Array.isArray(metas) && metas.length) {
        if (sawAi && prev?.sender === 'ai') {
          prev.toolCalls = [...(prev.toolCalls || []), ...metas.map(toolCallOf)];
          if (runId) prev.toolCallsRunId = runId;
        } else {
          // Ассистент этой крошки — в более старой странице: несём metas наверх.
          leadingMetas.push(...metas.map(toolCallOf));
        }
      }
      continue; // преамбулу как сообщение не рендерим
    }
    // След команды /compact: ряд без текста, весь смысл которого — в мете (см.
    // SummaryWriter.writeCompacted). Отдельным пузырём-плашкой, а не репликой ассистента.
    if (m.compact) {
      bubbles.push({
        mid: nextMessageId(),
        dbId: m.id ?? null,
        sender: SENDER.AI,
        compact: { messages: m.compact.messages, summaryChars: m.compact.summaryChars },
        // Токены самого раунда сжатия — тем же полем, что и у ответа: сжатие тоже обращение к
        // модели, и в итогах чата оно обязано считаться наравне. По ним же плашка говорит,
        // сколько контекст занимал до неё (см. contextBeforeCompact).
        ...(m.usage ? { usage: m.usage } : {}),
        timestamp: m.timestamp || null,
      });
      continue;
    }
    // След git-команды, выполненной пользователем: ряд USER без текста, весь
    // смысл которого — в мете (см. ChatHistoryService.appendGitEvent). Пузырём
    // от лица пользователя он был бы неправдой — человек ничего не написал.
    if (m.gitEvent) {
      bubbles.push({
        mid: nextMessageId(),
        dbId: m.id ?? null,
        sender: SENDER.USER,
        gitEvent: m.gitEvent,
        timestamp: m.timestamp || null,
      });
      continue;
    }
    if (type === 'system') continue; // прочие системные сообщения (напр. summary) не показываем
    // Протокольные TOOL-сообщения (ответы инструментов) — не для показа: их содержимое
    // видно через плашки/модалку деталей соответствующего сегмента.
    if (type === 'tool') continue;
    const metas = Array.isArray(m.toolInvocationMetas) ? m.toolInvocationMetas : [];
    // Токены прогона могут стоять на ряду, который пузырём не станет: остановка посреди работы
    // инструментов оставляет последним сегмент без текста, а бэкенд пишет токены именно
    // последнему. Отдаём их предыдущему пузырю ответа — иначе после перезагрузки прогон теряет
    // и плашку, и счётчик контекста, а тот показывает заниженное число прошлого прогона.
    // Пузырь обязан быть из ТОГО ЖЕ прогона: ход, не оставивший ни одного пузыря (остановлен до
    // первого текста), иначе затёр бы своим счётом ответ предыдущего прогона — и итоги по чату
    // недосчитались бы его. Некуда положить — счёт теряется, и это честнее чужой плашки.
    const carryUsageToPrev = () => {
      const prev = bubbles[bubbles.length - 1];
      const sameRun = !prev?.toolCallsRunId || !m.runId || prev.toolCallsRunId === m.runId;
      if (m.usage && sameRun && prev?.sender === SENDER.AI && !prev.compact && !prev.gitEvent) {
        prev.usage = m.usage;
      }
    };
    // Сегмент из одних tool_calls без текста и без сохранённых metas показывать нечем.
    if (type !== 'user' && !(m.content || '').trim() && !metas.length) {
      carryUsageToPrev();
      continue;
    }
    if (type !== 'user') sawAi = true;
    // Сегмент из одних tool_calls без текста: отдельный «пустой» пузырь визуально разрывает
    // ленту плашек, поэтому его вызовы приклеиваем к предыдущему AI-сегменту того же ответа.
    // Прикрепляем только при совместимых runId — callIndex уникален лишь в рамках прогона.
    if (type !== 'user' && !(m.content || '').trim()) {
      const prev = bubbles[bubbles.length - 1];
      // Плашка сжатия — не сегмент ответа: вызовы к ней не липнут (см. ниже attachLeadingMetas).
      if (
        prev?.sender === SENDER.AI &&
        !prev.compact &&
        !prev.gitEvent &&
        (!prev.toolCallsRunId || !m.runId || prev.toolCallsRunId === m.runId)
      ) {
        prev.toolCalls = [...(prev.toolCalls || []), ...metas.map(toolCallOf)];
        if (m.runId && !prev.toolCallsRunId) prev.toolCallsRunId = m.runId;
        carryUsageToPrev();
        continue;
      }
    }
    bubbles.push({
      mid: nextMessageId(),
      // id сообщения в БД — якорь для поиска по чату (find-бар, Ctrl+F): позволяет
      // сопоставить хит бэкенда с пузырём и понять, догружена ли страница с совпадением.
      dbId: m.id ?? null,
      text: m.content,
      sender: type === 'user' ? SENDER.USER : SENDER.AI,
      timestamp: m.timestamp || null,
      // Приложенное к вопросу (вложения) — чипы под текстом пузыря.
      ...(m.contextItems?.length ? { contextItems: m.contextItems } : {}),
      // Этим вопросом чат сменил проект — плашка-разделитель перед пузырём.
      ...(m.projectSwitchFrom ? { projectSwitch: { from: m.projectSwitchFrom, to: m.project } } : {}),
      // Вопрос задан во время прогона: ход открыт не им (см. trimActiveRunTail).
      ...(m.interjection ? { interjection: true } : {}),
      // Модель, написавшая ответ. У вопросов и у ответов старше этого поля её нет —
      // подпись тогда просто не рендерится (см. Message).
      ...(m.model && type !== 'user' ? { model: m.model } : {}),
      // Токены прогона: бэкенд пишет их одному ряду прогона — последнему (markRunResult),
      // так что плашка после перезагрузки встаёт туда же, куда её ставил редьюсер вживую.
      ...(m.usage && type !== 'user' ? { usage: m.usage } : {}),
      // Вызовы инструментов этого сегмента (раздельное сохранение): плашки под пузырём.
      ...(metas.length && type !== 'user'
        ? { toolCalls: metas.map(toolCallOf), ...(m.runId ? { toolCallsRunId: m.runId } : {}) }
        : {}),
    });
  }
  return { bubbles, leadingMetas };
};

// Обрезает хвостовые сегменты ассистента после последнего USER-сообщения. При
// активном прогоне это его частично сохранённые сегменты (преамбулы + плашки уже
// завершённых tool-циклов) — SSE-реплей текущего прогона пришлёт их заново, поэтому
// из загруженной истории их убираем: иначе перезагрузка страницы ПОСРЕДИ генерации
// показала бы ответ дважды (из БД + из реплея), пока прогон не завершится. Реплей
// начинается с RUN_STARTED, т.е. ровно с хода последнего USER-сообщения, так что
// пересоберёт этот хвост один раз. Если USER-сообщения на странице нет (очень длинный
// прогон вытеснил его на более старую страницу) — не трогаем: обрезать было бы нечем
// однозначно, а такой кейс редок.
export const trimActiveRunTail = (bubbles) => {
  let lastUser = -1;
  for (let i = bubbles.length - 1; i >= 0; i--) {
    // Ход открывает не всякий USER-пузырь. Ряд git-команды вопросом не является;
    // вопрос, отправленный во время прогона, задан внутри уже идущего хода. Обрезав
    // хвост по любому из них, оставили бы на экране ответ, который стрим сейчас
    // перепишет заново.
    if (opensATurn(bubbles[i])) {
      lastUser = i;
      break;
    }
  }
  // Отрезаются только незаконченные сегменты ответа. Ряды git-команд в хвосте
  // остаются: они уже сохранены в истории, и выбросив их, карточка вывода
  // пропадала бы на время прогона и возвращалась после перезагрузки. Пузыри
  // вопросов из этого же хвоста, наоборот, срезаются: они опубликованы событием
  // USER_MESSAGE внутри активного прогона, и реплей вернёт их сам.
  return lastUser < 0 ? bubbles : bubbles.filter((b, i) => i <= lastUser || !!b.gitEvent);
};

// Открывает ли пузырь ход разговора — фронтовый двойник ChatHistoryService.opensATurn.
const opensATurn = (bubble) => bubble.sender === SENDER.USER && !bubble.gitEvent && !bubble.interjection;

// Прицепляет «висячие» metas (крошки без ассистента в своей странице) к последнему
// AI-пузырю переданного набора. Возвращает остаток, который не удалось прицепить
// (если в наборе вообще нет ассистента) — его несём дальше вверх.
export const attachLeadingMetas = (bubbles, metas) => {
  if (!metas || !metas.length) return [];
  for (let i = bubbles.length - 1; i >= 0; i--) {
    if (bubbles[i].sender === SENDER.AI && !bubbles[i].compact) {
      bubbles[i] = { ...bubbles[i], toolCalls: [...(bubbles[i].toolCalls || []), ...metas] };
      return [];
    }
  }
  return metas;
};
