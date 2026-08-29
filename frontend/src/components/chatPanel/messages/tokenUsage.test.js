import { describe, test, expect } from 'vitest';
import {
  baseContextOf,
  cacheMissOf,
  cacheShare,
  chatUsageTotals,
  contextUsageOf,
  formatTokens,
  hasUsage,
  runInputGrowth,
  usageTooltip,
} from './tokenUsage';

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

  test('кэш-промах — это вход, за который заплачено по полной ставке', () => {
    expect(cacheMissOf({ promptTokens: 31100, cacheReadTokens: 24000 })).toBe(7100);
    expect(cacheMissOf({ promptTokens: 900 })).toBe(900);
    // Провайдеру, посчитавшему кэша больше, чем всего входа, верить незачем.
    expect(cacheMissOf({ promptTokens: 100, cacheReadTokens: 400 })).toBe(0);
    expect(cacheMissOf(null)).toBe(0);
  });

  test('подсказка отвечает тремя строками: занято, обработано нового, сгенерировано', () => {
    // Статистика за весь чат (total input, кэш, обращения) живёт во вкладке «Инфо» — здесь вопрос
    // только про этот прогон.
    const t = (key, vars) => `${key}:${JSON.stringify(vars)}`;
    const usage = { contextTokens: 38600, outputTokens: 2700, promptTokens: 65300, cacheReadTokens: 46300 };

    expect(usageTooltip(usage, t, 'head').split('\n')).toEqual([
      'head:{"context":"38.6k"}',
      'message.tokensMiss:{"input":"19.0k"}',
      'message.tokensOutput:{"output":"2.7k"}',
    ]);
  });

  describe('baseContextOf', () => {
    const ai = (usage) => ({ sender: 'ai', usage });

    test('системная часть — база ПЕРВОГО прогона: у следующих в неё входит вся история', () => {
      const messages = [
        { sender: 'user' },
        ai({ contextTokens: 12000, basePromptTokens: 9800 }),
        { sender: 'user' },
        ai({ contextTokens: 31000, basePromptTokens: 24000 }),
      ];

      expect(baseContextOf(messages)).toBe(9800);
    });

    test('у частично загруженной ленты первый прогон не первый в чате', () => {
      const messages = [ai({ contextTokens: 31000, basePromptTokens: 24000 })];

      expect(baseContextOf(messages, true)).toBeNull();
    });

    test('прогон, измеренный версией без этого поля, показывать нечем', () => {
      expect(baseContextOf([ai({ contextTokens: 12000 })])).toBeNull();
      expect(baseContextOf([{ sender: 'user' }])).toBeNull();
      expect(baseContextOf(undefined)).toBeNull();
    });
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

  describe('runInputGrowth', () => {
    const ai = (usage, rest = {}) => ({ sender: 'ai', usage, ...rest });
    const live = (usage) => ai(usage, { runId: 'r2' });

    test('прирост — живой контекст минус контекст прошлого прогона: вопрос и инструменты вместе', () => {
      const messages = [
        ai({ contextTokens: 10000, toolTokens: 800 }),
        { sender: 'user' },
        live({ contextTokens: 16400, toolTokens: 1200 }),
      ];

      expect(runInputGrowth(messages, 'r2')).toBe(6400);
    });

    test('первый прогон чата вырос со всего своего контекста', () => {
      const messages = [{ sender: 'user' }, live({ contextTokens: 5200, toolTokens: 300 })];

      expect(runInputGrowth(messages, 'r2')).toBe(5200);
    });

    test('неизмеренная история оставляет нижнюю границу — прирост внутри прогона', () => {
      // «До» неизвестно: прошлые ответы без замера. Весь контекст выдать за прирост нельзя.
      const messages = [ai(undefined), { sender: 'user' }, live({ contextTokens: 16400, toolTokens: 1200 })];

      expect(runInputGrowth(messages, 'r2')).toBe(1200);
    });

    test('«до» решает ближайший ответ: за неизмеренным прогоном к старому замеру не идём', () => {
      // Иначе рост неизмеренного прогона записался бы текущему.
      const messages = [
        ai({ contextTokens: 10000 }),
        ai(undefined, { runId: 'r1' }),
        { sender: 'user' },
        live({ contextTokens: 51000, toolTokens: 1200 }),
      ];

      expect(runInputGrowth(messages, 'r2')).toBe(1200);
    });

    test('локальный пузырь ошибки отправки историей не считается', () => {
      // За ним нет прогона (runId не появился) — первый настоящий ответ вырос со всего контекста.
      const messages = [
        { sender: 'ai', error: true, retryMode: 'resend' },
        { sender: 'user' },
        live({ contextTokens: 5200, toolTokens: 0 }),
      ];

      expect(runInputGrowth(messages, 'r2')).toBe(5200);
    });

    test('плашка сжатия обрывает поиск «до» — как и у счётчика в шапке', () => {
      const messages = [
        ai({ contextTokens: 90000 }),
        { sender: 'ai', compact: { messages: 40 } },
        live({ contextTokens: 6100, toolTokens: 700 }),
      ];

      expect(runInputGrowth(messages, 'r2')).toBe(700);
    });

    test('без живого замера показывать нечего — «неизвестно» это не ноль', () => {
      expect(runInputGrowth([ai({ contextTokens: 10000 }), { sender: 'ai', runId: 'r2' }], 'r2')).toBeNull();
      expect(runInputGrowth([], 'r2')).toBeNull();
      expect(runInputGrowth(undefined, null)).toBeNull();
    });

    test('контекст, ужавшийся после ручной чистки, не даёт отрицательного прироста', () => {
      const messages = [ai({ contextTokens: 20000 }), live({ contextTokens: 16000, toolTokens: 0 })];

      expect(runInputGrowth(messages, 'r2')).toBe(0);
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
