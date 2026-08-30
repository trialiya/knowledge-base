// Токены прогонов на фронте: формат и выборки по ленте. Считает их бэкенд (см. TokenUsageAdvisor),
// здесь только показ — в футере ответа и в шапке помещается одно короткое число, разбивка живёт в
// подсказке, а расширенная статистика по всему чату — во вкладке «Инфо».

import { isFullCompaction } from '@/constants/compactKind';
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
 * Ряд, которым кончается самая поздняя фоновая суммаризация в ленте, — по нему видно, какие замеры
 * она обесценила. Плашка такой суммаризации встаёт в СЕРЕДИНУ ленты (её время — время свёрнутого
 * куска), а записана она в момент применения, то есть позже всего, что к тому моменту в чате было.
 * Поэтому «свежесть» замера решает не место в ленте, а id ряда: прогон с id меньше этого мерил
 * историю, часть которой сводка уже заменила собой.
 *
 * `null` — фоновых плашек в загруженной ленте нет, обесценивать нечем. Ряд без `dbId` — созданный
 * прямо сейчас в этой вкладке, то есть заведомо новее любой плашки.
 */
const lastPartialCompactId = (messages) => {
  let last = null;
  for (const m of messages || []) {
    if (!m.compact || isFullCompaction(m.compact) || m.dbId == null) continue;
    if (last == null || m.dbId > last) last = m.dbId;
  }
  return last;
};

/**
 * Чем занят контекст чата сейчас — по последнему прогону, который это измерил. Ищем с конца, а не
 * суммируем: prompt каждого обращения уже включает всю историю до него, поэтому свежий замер и есть
 * ответ целиком (см. RunTokenUsage на бэке).
 *
 * Любая плашка сжатия обрывает поиск: замер выше неё говорит про историю, часть которой уже не
 * едет модели. Дальше всё зависит от того, сколько сжатие выбросило. После полного (/compact и
 * авто-compact) отвечает оценка по сводке — contextAfterCompact; после фоновой суммаризации не
 * отвечает никто: под плашкой остался живой хвост, и его размер не измерен и не оценивается.
 * Пустой счётчик здесь честнее числа, завышенного на весь сжатый кусок; точное придёт с первым же
 * ответом.
 *
 * Место в ленте для фоновой плашки этого не решает: она встаёт в середину, и поиск с конца
 * находит замеры ЗА ней — сделанные, пока сжатая ею голова истории была ещё живой. Отсюда сверка
 * по id (lastPartialCompactId): такой замер описывает контекст до применения сводки и завышен
 * ровно на неё.
 *
 * @param base системная часть контекста (baseContextOf) — нужна только для оценки после сжатия
 */
export const contextUsageOf = (messages, base) => {
  const applied = lastPartialCompactId(messages);
  for (let i = (messages?.length || 0) - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.compact) return isFullCompaction(m.compact) ? contextAfterCompact(m, base) : null;
    if (hasUsage(runUsage(m))) return applied != null && m.dbId != null && m.dbId < applied ? null : m.usage;
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
    // Плашка сжатия тоже несёт замер, но системной части чата в нём нет: у /compact
    // basePromptTokens — это всё прочитанное раундом окно, у фоновой суммаризации — вовсе чужой
    // системный промпт, суммаризатора.
    if (m.compact) continue;
    if (!hasUsage(runUsage(m))) continue;
    return Number(m.usage.basePromptTokens) || null;
  }
  return null;
};

/**
 * Во что обошлось сжатие по его же плашке: сколько контекст занимал до раунда. Это вход раунда —
 * провайдерский замер ровно того окна, которое сжатие выбросило (плюс системная часть, которую
 * плашка вычитает, и собственная инструкция сжатия на пару тысяч токенов: отделить её нечем,
 * prompt меряется целиком).
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
 * Экономия каждого ПОЛНОГО сжатия в ленте: mid плашки → {before, after, percent, estimated}.
 *
 * Оба числа — про ОДИН РАЗГОВОР, без системной части: системный промпт со схемами инструментов
 * сжатие не трогает, он стоит в обоих замерах одинаковым слагаемым и только занижает разницу,
 * отвечая на вопрос, которого никто не задавал. Отсюда и вычитание base из обоих концов (см.
 * baseContextOf), и правило «нет системной части — нет и чисел»: плашка обещала бы экономию
 * разговора, а показывала бы её вместе с чужим слагаемым.
 *
 * «До» — замер самого раунда (вход, который он оплатил). «После» — первый измеренный прогон за
 * плашкой, а пока его нет, оценка (см. contextAfterCompact), которая за вычетом системной части
 * равна написанной сводке; отсюда estimated, и его обязан показать интерфейс: до первого ответа
 * число приблизительное.
 *
 * У фоновой суммаризации экономии в этих числах нет: её раунд читал не контекст чата, а пересказ
 * сжимаемого куска своим промптом, и «до» из такого замера — число про другой запрос. Плашку она
 * получает без экономии, а поиск «после» для предыдущей — обрывает: контекст она тоже изменила.
 *
 * Одним проходом и картой, а не вопросом на каждую плашку: плашек в чате бывает несколько, и
 * каждая ищет свой «после» вперёд по ленте — поиск на плашку дал бы квадрат по длине ленты.
 *
 * @param partial история загружена не с начала — тогда системная часть неизвестна (baseContextOf),
 *     и чисел у плашек не будет вовсе
 */
export const compactSavingsIn = (messages, partial) => {
  const base = baseContextOf(messages, partial);
  const savings = new Map();
  if (base == null) return savings;
  let pending = null;
  // Оба конца — за вычетом системной части. Отрицательным ни один из них по смыслу не бывает
  // (base входит в каждый замер слагаемым), но токенизация склейки не равна сумме токенизаций
  // частей, и на пустом разговоре разница уходит в единицы токенов — оттуда и Math.max.
  const settle = (notice, after, estimated) => {
    const measured = contextBeforeCompact(notice);
    if (!measured || after == null) return;
    const before = Math.max(0, measured - base);
    if (!before) return;
    const left = Math.max(0, after - base);
    savings.set(notice.mid, {
      before,
      after: left,
      estimated,
      percent: Math.max(0, Math.round((1 - left / before) * 100)),
    });
  };
  for (const m of messages || []) {
    if (m.compact) {
      // Предыдущая плашка так и не дождалась замера: между двумя сжатиями ответа не было.
      if (pending) settle(pending, contextAfterCompact(pending, base)?.contextTokens ?? null, true);
      pending = isFullCompaction(m.compact) ? m : null;
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
 * Деньги, унесённые плашкой сжатия (`compact.carried`), складываются наравне: это оплаченные
 * раунды фоновых сводок, которые сжатие выбросило вместе с их куском истории, и своего ряда у них
 * не осталось. В `runs` они не идут — прогоном в ленте они больше не представлены, — а вот в счёт
 * провайдера идут, и без них Total разошёлся бы с ним ровно на их стоимость.
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
  const addMoney = (usage) => {
    totals.outputTokens += Number(usage.outputTokens || 0);
    totals.promptTokens += Number(usage.promptTokens || 0);
    totals.cacheReadTokens += Number(usage.cacheReadTokens || 0);
    totals.cacheWriteTokens += Number(usage.cacheWriteTokens || 0);
    totals.modelCalls += Number(usage.modelCalls || 0);
  };
  let carried = false;
  for (const m of messages || []) {
    if (m.compact?.carried) {
      addMoney(m.compact.carried);
      carried = true;
    }
    if (!hasUsage(m.usage)) continue;
    totals.runs += 1;
    addMoney(m.usage);
  }
  return totals.runs > 0 || carried ? totals : null;
};
