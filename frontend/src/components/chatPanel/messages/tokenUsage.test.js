import { describe, test, expect } from 'vitest';
import { billedTokens, cacheShare, formatTokens, hasUsage } from './tokenUsage';

describe('tokenUsage', () => {
  test('короткие числа показываются как есть', () => {
    expect(formatTokens(0)).toBe('0');
    expect(formatTokens(940)).toBe('940');
  });

  test('тысячи сокращаются, дробная часть — только до 100k', () => {
    expect(formatTokens(12345)).toBe('12.3k');
    expect(formatTokens(345678)).toBe('346k');
    expect(formatTokens(1200000)).toBe('1.2M');
  });

  test('на границах единиц не появляется ни «100.0k», ни «1000k»', () => {
    expect(formatTokens(99999)).toBe('100k');
    expect(formatTokens(999499)).toBe('999k');
    expect(formatTokens(999500)).toBe('1.0M');
  });

  test('плашки нет, пока ничего не насчитано', () => {
    expect(hasUsage(null)).toBe(false);
    expect(hasUsage({ contextTokens: 0 })).toBe(false);
    expect(hasUsage({ contextTokens: 1 })).toBe(true);
  });

  test('оплачено — сумма всех prompt-ов плюс выход, а не занятый контекст', () => {
    // Три обращения по ~10k prompt каждое при 10.7k реально занятого контекста.
    expect(billedTokens({ contextTokens: 11000, promptTokens: 31100, outputTokens: 650 })).toBe(31750);
    expect(billedTokens(undefined)).toBe(0);
  });

  test('доля кэша считается от оплаченного prompt-а', () => {
    expect(cacheShare({ promptTokens: 31100, cacheReadTokens: 20000 })).toBe(64);
    // Первое обращение прогона кэш ещё не читает — делить на ноль тут нечего.
    expect(cacheShare({ promptTokens: 0, cacheReadTokens: 0 })).toBe(0);
  });
});
