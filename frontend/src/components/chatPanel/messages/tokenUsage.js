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
 * Какая доля total input прочитана из кэша, в процентах. Именно она объясняет разрыв между
 * занятым контекстом и суммарным входом: повторная часть у провайдера идёт по ставке кэша.
 */
export const cacheShare = (usage) => {
  const prompt = Number(usage?.promptTokens || 0);
  const cached = Number(usage?.cacheReadTokens || 0);
  return prompt > 0 ? Math.round((cached / prompt) * 100) : 0;
};

/**
 * Разбивка токенов для подсказки: строки одна под другой. Первая своя у каждого места показа
 * (плашка говорит про свой ответ, счётчик в шапке — про чат сейчас), остальные общие — держать их
 * двумя копиями значит однажды поправить одну.
 *
 * @param headKey ключ первой строки; получает {@code context} — занятый контекст
 */
export const usageTooltip = (usage, t, headKey) =>
  [
    t(headKey, { context: formatTokens(usage.contextTokens) }),
    usage.toolTokens > 0 ? t('message.tokensTools', { tools: formatTokens(usage.toolTokens) }) : null,
    t('message.tokensOutput', { output: formatTokens(usage.outputTokens) }),
    t('message.tokensInput', { input: formatTokens(usage.promptTokens), calls: usage.modelCalls }),
    usage.cacheReadTokens > 0
      ? t('message.tokensCached', { cached: formatTokens(usage.cacheReadTokens), percent: cacheShare(usage) })
      : null,
  ]
    .filter(Boolean)
    .join('\n');

/**
 * Чем занят контекст чата сейчас — по последнему прогону, который это измерил. Ищем с конца, а не
 * суммируем: prompt каждого обращения уже включает всю историю до него, поэтому свежий замер и есть
 * ответ целиком (см. RunTokenUsage на бэке).
 *
 * Плашка сжатия обрывает поиск: /compact выбросил из контекста почти всё, и замер выше неё говорит
 * про историю, которой больше нет. Показать нечего до следующего ответа — и это честнее, чем число,
 * завышенное в разы.
 */
export const contextUsageOf = (messages) => {
  for (let i = (messages?.length || 0) - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.compact) return null;
    if (hasUsage(m.usage)) return m.usage;
  }
  return null;
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
