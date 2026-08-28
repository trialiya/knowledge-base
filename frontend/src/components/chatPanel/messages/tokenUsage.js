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
