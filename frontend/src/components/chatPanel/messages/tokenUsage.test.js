import { describe, test, expect } from 'vitest';
import { cacheShare, chatUsageTotals, contextUsageOf, formatTokens, hasUsage } from './tokenUsage';

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

  test('доля кэша считается от total input', () => {
    expect(cacheShare({ promptTokens: 31100, cacheReadTokens: 20000 })).toBe(64);
    // Первое обращение прогона кэш ещё не читает — делить на ноль тут нечего.
    expect(cacheShare({ promptTokens: 0, cacheReadTokens: 0 })).toBe(0);
  });

  describe('contextUsageOf', () => {
    const ai = (usage) => ({ sender: 'ai', usage });

    test('контекст берётся у последнего измеренного ответа, а не суммируется', () => {
      const messages = [ai({ contextTokens: 4000 }), { sender: 'user' }, ai({ contextTokens: 11000 })];

      expect(contextUsageOf(messages)).toEqual({ contextTokens: 11000 });
    });

    test('ответ без замера пропускается — измерял предыдущий прогон', () => {
      // Прогон на эндпоинте без usage в стриме не измерен вовсе, и это не ноль.
      const messages = [ai({ contextTokens: 11000 }), ai(undefined)];

      expect(contextUsageOf(messages)).toEqual({ contextTokens: 11000 });
    });

    test('после /compact счётчика нет: замер выше плашки описывает выброшенную историю', () => {
      const messages = [ai({ contextTokens: 90000 }), { sender: 'ai', compact: { messages: 40 } }];

      expect(contextUsageOf(messages)).toBeNull();
    });

    test('в чате без единого замера показывать нечего', () => {
      expect(contextUsageOf([{ sender: 'user' }])).toBeNull();
      expect(contextUsageOf(undefined)).toBeNull();
    });
  });

  describe('chatUsageTotals', () => {
    const run = (usage) => ({ sender: 'ai', usage });

    test('складывает выход, вход, кэш и обращения — но не контекст', () => {
      const totals = chatUsageTotals([
        run({ contextTokens: 4000, outputTokens: 300, promptTokens: 9000, cacheReadTokens: 2000, modelCalls: 2 }),
        { sender: 'user' },
        run({ contextTokens: 11000, outputTokens: 320, promptTokens: 31100, cacheReadTokens: 24000, modelCalls: 3 }),
      ]);

      expect(totals).toEqual({
        runs: 2,
        outputTokens: 620,
        promptTokens: 40100,
        cacheReadTokens: 26000,
        cacheWriteTokens: 0,
        modelCalls: 5,
      });
      // Контекст у прогонов общий и растёт, а не набирается: 4000 + 11000 было бы числом ниоткуда.
      expect(totals).not.toHaveProperty('contextTokens');
    });

    test('чат без единого измеренного прогона итогов не даёт', () => {
      expect(chatUsageTotals([{ sender: 'user' }, run(undefined)])).toBeNull();
      expect(chatUsageTotals([])).toBeNull();
    });
  });
});
