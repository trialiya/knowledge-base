// Токены прогона в подписи под ответом. Считает их бэкенд (см. TokenUsageAdvisor), здесь только
// формат: в футере рядом со временем и моделью помещается пара коротких чисел, а разбивка живёт
// в подсказке.

/** Порог, с которого число сокращается до «k». Ниже него цифры и так короткие. */
const THOUSAND = 1000;

/**
 * Компактное число токенов: 940 → «940», 12 345 → «12.3k», 1 200 000 → «1.2M».
 * Дробную часть у «k» показываем только до 100k — дальше она шире, чем полезна.
 */
export const formatTokens = (value) => {
  const n = Number(value) || 0;
  if (n < THOUSAND) return String(n);
  if (n < THOUSAND * THOUSAND) {
    const k = n / THOUSAND;
    return `${k < 100 ? k.toFixed(1) : Math.round(k)}k`;
  }
  return `${(n / THOUSAND / THOUSAND).toFixed(1)}M`;
};

/**
 * Есть ли что показывать. Прогон без единого замера (эндпоинт не поддерживает usage в стриме)
 * плашки не получает вовсе — «неизвестно» это не ноль.
 */
export const hasUsage = (usage) => !!usage && Number(usage.totalTokens) > 0;
