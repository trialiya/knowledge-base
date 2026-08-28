// Токены прогона в подписи под ответом. Считает их бэкенд (см. TokenUsageAdvisor), здесь только
// формат: в футере рядом со временем и моделью помещается одно короткое число, а разбивка живёт
// в подсказке.

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
 * Оплаченное за прогон: сумма prompt'ов всех обращений к модели плюс сгенерированное. У ответа с
 * инструментами это в разы больше занятого контекста — каждое обращение несёт историю заново, —
 * поэтому число живёт в подсказке, а не в плашке.
 */
export const billedTokens = (usage) => Number(usage?.promptTokens || 0) + Number(usage?.outputTokens || 0);

/**
 * Какая доля оплаченного prompt'а прочитана из кэша, в процентах. Именно она объясняет разрыв
 * между занятым контекстом и оплаченным: повторная часть тарифицируется по ставке кэша.
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
    t('message.tokensBilled', { billed: formatTokens(billedTokens(usage)), calls: usage.modelCalls }),
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
