// Токены прогонов на фронте: формат и выборки по ленте. Считает их бэкенд (см. TokenUsageAdvisor),
// здесь только показ — в футере ответа и в шапке помещается одно короткое число, разбивка живёт в
// подсказке, а расширенная статистика по всему чату — во вкладке «Инфо».

import { SENDER } from '@/constants/messageSender';

const THOUSAND = 1000;
const MILLION = THOUSAND * THOUSAND;

/**
 * Порог перехода на следующую единицу. Сравниваем ДО округления: 99 999 округлилось бы в «100.0k»
 * (шире, чем влезает), а 999 500 — в «1000k» вместо «1.0M».
 */
const withoutFraction = 99.95;
const nextUnit = 999.5;

/** Компактное число: 940 → «940», 12 345 → «12.3k», 99 999 → «100k», 1 200 000 → «1.2M». */
export const formatTokens = (value) => {
  const n = Number(value) || 0;
  if (n < THOUSAND) return String(n);
  const k = n / THOUSAND;
  if (k >= nextUnit) return `${(n / MILLION).toFixed(1)}M`;
  // Дробную часть показываем только до сотни тысяч — дальше она шире, чем полезна.
  return `${k < withoutFraction ? k.toFixed(1) : Math.round(k)}k`;
};

/**
 * Есть ли что показывать. Прогон без единого замера (эндпоинт не поддерживает usage в стриме)
 * плашки не получает вовсе — «неизвестно» это не ноль.
 */
export const hasUsage = (usage) => !!usage && Number(usage.contextTokens) > 0;

/**
 * Замер, который описывает контекст чата, — то есть замер прогона. На ряду ПОЛЬЗОВАТЕЛЯ замер
 * контекстом не является: там он бывает у одного случая — несостоявшегося сжатия, записанного на
 * строку своей команды (см. CompactService.spentRound), — и описывает окно, которое раунд прочитал,
 * вместе с его собственной инструкцией, при том что само окно осталось в чате как было.
 *
 * В счёт провайдера такой замер идёт наравне со всеми (chatUsageTotals: деньги потрачены), а в
 * «сколько занято сейчас» и в «стало» после сжатия — нет.
 */
const runUsage = (m) => (m?.sender === SENDER.USER ? null : m?.usage);

/**
 * Какая доля total input прочитана из кэша, в процентах. Именно она объясняет разрыв между
 * занятым контекстом и суммарным входом: повторная часть у провайдера идёт по ставке кэша.
 */
export const cacheShare = (usage) => {
  const prompt = Number(usage?.promptTokens || 0);
  const cached = Number(usage?.cacheReadTokens || 0);
  return prompt > 0 ? Math.round((cached / prompt) * 100) : 0;
};

/**
 * Вход, оплаченный по полной ставке: total input минус прочитанное из кэша. Это и есть то новое,
 * что прогон добавил провайдеру к обработке, — остальное он уже видел.
 */
export const cacheMissOf = (usage) =>
  Math.max(0, Number(usage?.promptTokens || 0) - Number(usage?.cacheReadTokens || 0));

/**
 * Разбивка токенов для подсказки: три строки одна под другой. Первая своя у каждого места показа
 * (плашка говорит про свой ответ, счётчик в шапке — про чат сейчас), остальные общие — держать их
 * двумя копиями значит однажды поправить одну.
 *
 * Больше трёх строк подсказке не нужно: total input, кэш и число обращений — статистика за весь
 * чат, её место во вкладке «Инфо», где числа стоят рядом и сравниваются. Здесь же вопрос один — во
 * что обошёлся ЭТОТ прогон: сколько после него занято, сколько нового пришлось обработать и сколько
 * сгенерировано.
 *
 * @param headKey ключ первой строки; получает {@code context} — занятый контекст
 */
export const usageTooltip = (usage, t, headKey) =>
  [
    t(headKey, { context: formatTokens(usage.contextTokens) }),
    t('message.tokensMiss', { input: formatTokens(cacheMissOf(usage)) }),
    t('message.tokensOutput', { output: formatTokens(usage.outputTokens) }),
  ].join('\n');

/**
 * Занятый контекст числом для показа. Оценка (см. contextUsageOf) получает «~»: разница между
 * «примерно столько» и «столько» здесь принципиальна — первое сойдётся со следующим ответом лишь
 * приблизительно, и молча выдавать его за замер нельзя.
 */
export const formatContext = (usage) => (usage?.estimated ? '~' : '') + formatTokens(usage?.contextTokens);

/**
 * Контекст сразу после сжатия — единственное место, где число оценивается, а не берётся замером.
 *
 * Замерить его некому: провайдер меряет запросы, а между сжатием и следующим вопросом запросов нет.
 * Складывается из системной части (она сжатием не тронута) и документа, который написала модель, —
 * это выход раунда сжатия, и он же теперь весь разговор. Мимо оценки проходят обёртка сводки
 * (десятки токенов) и то, что первый вопрос чата входит в системную часть (см. baseContextOf), а
 * токенизация склейки не равна сумме токенизаций частей.
 *
 * Точное число приезжает само — с первым же ответом после сжатия, и заменяет оценку.
 *
 * `null` — оценивать не из чего: системная часть неизвестна либо раунд сжатия не измерен. Пустой
 * счётчик здесь честнее ноля и честнее числа до сжатия, завышенного в разы.
 */
const contextAfterCompact = (notice, base) =>
  base == null || !hasUsage(notice.usage)
    ? null
    : { contextTokens: base + Number(notice.usage.outputTokens || 0), estimated: true };

/**
 * Чем занят контекст чата сейчас — по последнему прогону, который это измерил. Ищем с конца, а не
 * суммируем: prompt каждого обращения уже включает всю историю до него, поэтому свежий замер и есть
 * ответ целиком (см. RunTokenUsage на бэке).
 *
 * Плашка сжатия обрывает поиск: /compact выбросил из контекста почти всё, и замер выше неё говорит
 * про историю, которой больше нет. Дальше отвечает оценка (contextAfterCompact) — её собственный
 * замер описывает выброшенное окно и текущим контекстом быть не может.
 *
 * @param base системная часть контекста (baseContextOf) — нужна только для оценки после сжатия
 */
export const contextUsageOf = (messages, base) => {
  for (let i = (messages?.length || 0) - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.compact) return contextAfterCompact(m, base);
    if (hasUsage(runUsage(m))) return m.usage;
  }
  return null;
};

/**
 * Системная часть контекста: сколько занято ещё до разговора — системный промпт со схемами
 * инструментов плюс сам первый вопрос. Это `basePromptTokens` (prompt первого обращения) ПЕРВОГО
 * измеренного прогона чата: раньше него в контексте нет ничего, что написали бы в этом чате, а у
 * любого следующего прогона в базу входит уже вся история до него.
 *
 * Сжатие эту цифру не отменяет: /compact выбрасывает разговор, а системную часть — нет.
 *
 * `null` — показывать нечего: лента загружена не с начала (тогда её первый прогон не первый в чате
 * и число было бы завышено), либо прогон измерен версией без этого поля.
 *
 * @param partial история чата загружена частично (`chat.hasMore`)
 */
export const baseContextOf = (messages, partial) => {
  if (partial) return null;
  for (const m of messages || []) {
    // Плашка сжатия тоже несёт замер, но её basePromptTokens — это всё окно, которое сжатие
    // прочитало: у раунда из одного обращения «первый prompt» и есть весь его вход.
    if (m.compact) continue;
    if (!hasUsage(runUsage(m))) continue;
    return Number(m.usage.basePromptTokens) || null;
  }
  return null;
};

/**
 * Во что обошлось сжатие по его же плашке: сколько контекст занимал до раунда. Это вход раунда —
 * провайдерский замер ровно того окна, которое сжатие выбросило (плюс собственная инструкция сжатия
 * на пару тысяч токенов: отделить её нечем, prompt меряется целиком).
 *
 * `null` — раунд не измерен (эндпоинт не отдаёт usage либо сжатие прошло версией без этого поля).
 */
export const contextBeforeCompact = (notice) =>
  hasUsage(notice?.usage) ? Number(notice.usage.promptTokens) || null : null;

/**
 * Во что обошёлся контекст следующему запросу: prompt его первого обращения — системная часть,
 * сводка и сам вопрос, без наросшего за прогон. Именно это и есть «стало» для плашки сжатия;
 * contextTokens того же прогона включал бы ещё и всё, что он дочитал инструментами.
 *
 * Запасной вариант — contextTokens: у прогонов, записанных до появления basePromptTokens, другого
 * числа нет, и завышенное «стало» честнее пустого прочерка.
 */
const startingContextOf = (usage) => Number(usage.basePromptTokens) || Number(usage.contextTokens) || null;

/**
 * Экономия каждого сжатия в ленте: mid плашки → {before, after, percent, estimated}.
 *
 * «До» — замер самого раунда (вход, который он оплатил). «После» — первый измеренный прогон за
 * плашкой, а пока его нет, оценка по системной части (см. contextAfterCompact); отсюда estimated,
 * и его обязан показать интерфейс: до первого ответа число приблизительное.
 *
 * Одним проходом и картой, а не вопросом на каждую плашку: плашек в чате бывает несколько, и
 * каждая ищет свой «после» вперёд по ленте — поиск на плашку дал бы квадрат по длине ленты.
 *
 * @param partial история загружена не с начала — тогда системная часть неизвестна (baseContextOf),
 *     и плашка без измеренного ответа за собой останется с одним «до»
 */
export const compactSavingsIn = (messages, partial) => {
  const base = baseContextOf(messages, partial);
  const savings = new Map();
  let pending = null;
  const settle = (notice, after, estimated) => {
    const before = contextBeforeCompact(notice);
    if (!before) return;
    savings.set(notice.mid, {
      before,
      after,
      estimated,
      percent: after == null ? null : Math.max(0, Math.round((1 - after / before) * 100)),
    });
  };
  for (const m of messages || []) {
    if (m.compact) {
      // Предыдущая плашка так и не дождалась замера: между двумя сжатиями ответа не было.
      if (pending) settle(pending, contextAfterCompact(pending, base)?.contextTokens ?? null, true);
      pending = m;
      continue;
    }
    if (pending && hasUsage(runUsage(m))) {
      settle(pending, startingContextOf(m.usage), false);
      pending = null;
    }
  }
  if (pending) settle(pending, contextAfterCompact(pending, base)?.contextTokens ?? null, true);
  return savings;
};

/**
 * Прирост input за идущий прогон — для строки над полем ввода. Считается разницей между живым
 * замером прогона и контекстом до него, то есть включает и сам вопрос с вложениями, и всё, что
 * дочитали инструменты. Когда «до» неизвестно — история чата не измерена или отрезана плашкой
 * сжатия, — остаётся честная нижняя граница: прирост внутри самого прогона (toolTokens).
 *
 * `null` — прогон ещё ничего не измерил (или провайдер не измерит вовсе): показывать нечего.
 */
export const runInputGrowth = (messages, runId) => {
  if (!runId) return null;
  let live = null;
  let beforeContext = null;
  let aiBefore = false;
  for (let i = (messages?.length || 0) - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.runId === runId) {
      if (!live && hasUsage(m.usage)) live = m.usage;
      continue;
    }
    if (m.compact) {
      // История до сжатия была, но её замеры описывают выброшенный контекст — «до» неизвестно.
      aiBefore = true;
      break;
    }
    if (m.sender !== SENDER.AI || m.gitEvent) continue;
    // Локальный пузырь ошибки отправки: прогона за ним нет (runId не появился), контекст он не
    // растил — историей не считается.
    if (m.error && !m.runId) continue;
    // «До» решает ближайший настоящий ответ, и только он: замер у прогона стоит на последнем
    // сегменте, так что с конца он и встретится. Идти дальше, мимо неизмеренного прогона к
    // более старому замеру, нельзя — рост неизмеренного записался бы этому прогону.
    aiBefore = true;
    if (hasUsage(m.usage)) beforeContext = Number(m.usage.contextTokens);
    break;
  }
  if (!hasUsage(live)) return null;
  if (!aiBefore) return Number(live.contextTokens);
  if (beforeContext != null) return Math.max(0, Number(live.contextTokens) - beforeContext);
  return Number(live.toolTokens) || 0;
};

/**
 * Итоги по чату: что складывается по прогонам, а что нет.
 *
 * Складываются output, total input, кэш и число обращений — каждое из них у прогона своё, и в
 * соседний прогон не входит. `contextTokens` НЕ складывается ни при каких условиях: контекст у
 * прогонов общий и растёт, а не набирается, и сумма по ним была бы просто числом ниоткуда.
 * «Сколько занято сейчас» отвечает contextUsageOf.
 *
 * `null` — ни один прогон чата не измерен: показывать нечего, и ноль здесь был бы неправдой.
 */
export const chatUsageTotals = (messages) => {
  const totals = {
    runs: 0,
    outputTokens: 0,
    promptTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    modelCalls: 0,
  };
  for (const m of messages || []) {
    if (!hasUsage(m.usage)) continue;
    totals.runs += 1;
    totals.outputTokens += Number(m.usage.outputTokens || 0);
    totals.promptTokens += Number(m.usage.promptTokens || 0);
    totals.cacheReadTokens += Number(m.usage.cacheReadTokens || 0);
    totals.cacheWriteTokens += Number(m.usage.cacheWriteTokens || 0);
    totals.modelCalls += Number(m.usage.modelCalls || 0);
  }
  return totals.runs > 0 ? totals : null;
};
