import { describe, test, expect } from 'vitest';
import {
  baseContextOf,
  cacheMissOf,
  cacheShare,
  compactSavingsIn,
  contextUsageOf,
  formatContext,
  formatTokens,
  hasUsage,
  runInputGrowth,
  totalOf,
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

    test('плашка сжатия системной частью не притворяется: её база — всё прочитанное окно', () => {
      // У раунда из одного обращения basePromptTokens и есть весь его вход.
      const messages = [
        { sender: 'ai', compact: { messages: 40 }, usage: { contextTokens: 170000, basePromptTokens: 169000 } },
        ai({ contextTokens: 13000, basePromptTokens: 12000 }),
      ];

      expect(baseContextOf(messages)).toBe(12000);
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

    test('после /compact счётчик оценивается: системная часть плюс написанная сводка', () => {
      const messages = [
        ai({ contextTokens: 90000 }),
        { sender: 'ai', compact: { messages: 40 }, usage: { contextTokens: 91000, outputTokens: 1200 } },
      ];

      // 9800 (системная часть) + 1200 (сводка) — и это оценка, а не замер: провайдер меряет
      // запросы, а между сжатием и следующим вопросом запросов нет.
      expect(contextUsageOf(messages, 9800)).toEqual({ contextTokens: 11000, estimated: true });
      expect(formatContext(contextUsageOf(messages, 9800))).toBe('~11.0k');
    });

    test('замер выше плашки текущим контекстом не становится ни при каких условиях', () => {
      const messages = [ai({ contextTokens: 90000 }), { sender: 'ai', compact: { messages: 40 } }];

      // Системная часть известна, но сам раунд не измерен — складывать не с чем.
      expect(contextUsageOf(messages, 9800)).toBeNull();
      // И тем более когда неизвестна и она.
      expect(contextUsageOf(messages)).toBeNull();
    });

    test('первый же ответ после сжатия вытесняет оценку замером', () => {
      const messages = [
        { sender: 'ai', compact: { messages: 40 }, usage: { contextTokens: 91000, outputTokens: 1200 } },
        ai({ contextTokens: 13400 }),
      ];

      expect(contextUsageOf(messages, 9800)).toEqual({ contextTokens: 13400 });
    });

    test('в чате без единого замера показывать нечего', () => {
      expect(contextUsageOf([{ sender: 'user' }])).toBeNull();
      expect(contextUsageOf(undefined)).toBeNull();
    });

    test('после фоновой суммаризации счётчик пуст: живой хвост под плашкой не измерен', () => {
      const messages = [
        ai({ contextTokens: 90000 }),
        {
          sender: 'ai',
          compact: { messages: 40, kind: 'SUMMARIZE' },
          usage: { contextTokens: 62000, outputTokens: 1200 },
        },
      ];

      // Оценивать нечем: сводка заменила только начало истории, а сколько занимает оставшийся
      // хвост, не знает никто — и 90k выше плашки тем более не ответ.
      expect(contextUsageOf(messages, 9800)).toBeNull();
    });

    test('замер прогона, который шёл до применения сводки, счётчиком не становится', () => {
      // Плашка фоновой суммаризации стоит в СЕРЕДИНЕ ленты — ответ ниже неё старше её самой
      // (dbId 40 против 41): он мерил историю, начало которой сводка уже заменила собой.
      const messages = [
        { sender: 'ai', dbId: 41, compact: { messages: 40, kind: 'SUMMARIZE' } },
        { ...ai({ contextTokens: 90000 }), dbId: 40 },
      ];

      expect(contextUsageOf(messages, 9800)).toBeNull();
    });

    test('первый же ответ после применения сводки счётчик восстанавливает', () => {
      const messages = [
        { sender: 'ai', dbId: 41, compact: { messages: 40, kind: 'SUMMARIZE' } },
        { ...ai({ contextTokens: 30000 }), dbId: 42 },
      ];

      expect(contextUsageOf(messages, 9800)).toEqual({ contextTokens: 30000 });
    });
  });

  describe('compactSavingsIn', () => {
    const notice = (mid, usage) => ({ mid, sender: 'ai', compact: { messages: 40 }, usage });
    const ai = (usage) => ({ sender: 'ai', usage });

    test('«до» — вход раунда, «после» — начало следующего запроса, оба замерены', () => {
      const messages = [
        ai({ contextTokens: 168000, basePromptTokens: 9800 }),
        notice(7, { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 }),
        ai({ contextTokens: 15000, basePromptTokens: 12000 }),
      ];

      // Оба конца без системной части (9800): 159 200 → 2 200, сжали на 99%. «После» берётся из
      // базы следующего прогона, а не из его contextTokens — тот включал бы ещё и всё, что прогон
      // дочитал инструментами.
      expect(compactSavingsIn(messages)).toEqual(
        new Map([[7, { before: 159200, after: 2200, estimated: false, percent: 99 }]]),
      );
    });

    test('до первого ответа «после» — это написанная сводка, и число помечено оценкой', () => {
      const messages = [
        ai({ contextTokens: 168000, basePromptTokens: 9800 }),
        notice(7, { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 }),
      ];

      // Оценка «системная часть + сводка» за вычетом системной части — это ровно сводка.
      expect(compactSavingsIn(messages).get(7)).toEqual({
        before: 159200,
        after: 1200,
        estimated: true,
        percent: 99,
      });
    });

    test('своё «после» есть у каждого сжатия, даже когда между ними не отвечали', () => {
      const messages = [
        ai({ contextTokens: 168000, basePromptTokens: 9800 }),
        notice(7, { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 }),
        notice(8, { contextTokens: 12000, promptTokens: 11000, outputTokens: 900 }),
        ai({ contextTokens: 15000, basePromptTokens: 12000 }),
      ];

      const savings = compactSavingsIn(messages);
      // Первое сжатие замера за собой не дождалось — оценка; второе дождалось.
      expect(savings.get(7)).toMatchObject({ before: 159200, estimated: true });
      expect(savings.get(8)).toEqual({ before: 1200, after: 2200, estimated: false, percent: 0 });
    });

    test('у фоновой суммаризации экономии нет: её раунд мерил не контекст чата', () => {
      const summarized = {
        mid: 8,
        sender: 'ai',
        compact: { messages: 40, kind: 'SUMMARIZE' },
        usage: { contextTokens: 62000, promptTokens: 60000, outputTokens: 1200 },
      };
      const messages = [
        ai({ contextTokens: 168000, basePromptTokens: 9800 }),
        notice(7, { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 }),
        summarized,
        ai({ contextTokens: 15000, basePromptTokens: 12000 }),
      ];

      const savings = compactSavingsIn(messages);
      expect(savings.has(8)).toBe(false);
      // И «после» предыдущей плашки такая суммаризация обрывает: контекст она тоже изменила.
      expect(savings.get(7)).toMatchObject({ before: 159200, estimated: true });
    });

    test('неизмеренное сжатие в карту не попадает: «сэкономили ничего» — неправда', () => {
      const messages = [
        ai({ contextTokens: 168000, basePromptTokens: 9800 }),
        notice(7, undefined),
        ai({ contextTokens: 13000 }),
      ];

      expect(compactSavingsIn(messages).size).toBe(0);
    });

    test('без системной части чисел нет вовсе: вычитать её не из чего', () => {
      const messages = [notice(7, { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 })];

      // Лента загружена не с начала — системную часть взять неоткуда (см. baseContextOf), а
      // показать «до» вместе с ней значило бы выдать за экономию разговора чужое слагаемое.
      expect(compactSavingsIn(messages, true).size).toBe(0);
    });
  });

  // Замер на ряду пользователя бывает у одного случая — несостоявшегося сжатия, записанного на
  // строку своей команды (CompactService.spentRound). Деньги за него заплачены, но контекст чата
  // он не описывает: это прочитанное раундом окно плюс его собственная инструкция, а окно осталось
  // в чате как было.
  describe('замер несостоявшегося сжатия', () => {
    const spent = { contextTokens: 169040, promptTokens: 169000, basePromptTokens: 169000, outputTokens: 40 };
    const command = { mid: 9, sender: 'user', usage: { ...spent, modelCalls: 1 } };

    test('но не выдаёт себя ни за занятый контекст, ни за системную часть', () => {
      const messages = [{ sender: 'ai', usage: { contextTokens: 12000, basePromptTokens: 9800 } }, command];

      expect(contextUsageOf(messages)).toEqual({ contextTokens: 12000, basePromptTokens: 9800 });
      expect(baseContextOf(messages)).toBe(9800);
    });

    test('и «стало» после сжатия им не закрывается — его закрывает ответ', () => {
      const messages = [
        { sender: 'ai', usage: { contextTokens: 168000, basePromptTokens: 9800 } },
        {
          mid: 7,
          sender: 'ai',
          compact: { messages: 40 },
          usage: { contextTokens: 170200, promptTokens: 169000, outputTokens: 1200 },
        },
        command,
        { sender: 'ai', usage: { contextTokens: 15000, basePromptTokens: 12000 } },
      ];

      // «После» — база ответа (12 000) без системной части (9 800), а не замер команды.
      expect(compactSavingsIn(messages).get(7)).toMatchObject({ after: 2200, estimated: false });
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

  describe('totalOf', () => {
    test('обычно это вход плюс выход', () => {
      expect(totalOf({ promptTokens: 81_100, outputTokens: 1_100, totalTokens: 82_200 })).toBe(82_200);
    });

    // Провайдер считает reasoning-токены сверх видимого выхода, а платит за них клиент: своё
    // `total` он присылает больше суммы частей, и итог обязан показать именно его.
    test('но у модели с reasoning-токенами берётся счёт провайдера', () => {
      expect(totalOf({ promptTokens: 81_100, outputTokens: 1_100, totalTokens: 96_000 })).toBe(96_000);
    });

    // У прогонов, записанных до появления поля, своего `total` нет — сумма частей честнее нуля.
    test('у записи без этого поля остаётся сумма частей', () => {
      expect(totalOf({ promptTokens: 11_000, outputTokens: 400 })).toBe(11_400);
      expect(totalOf(null)).toBe(0);
    });
  });
});
