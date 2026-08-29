/**
 * Фикстуры вкладки «Usage» правой панели чата (chatPanel/center/ChatUsage.jsx).
 *
 * Компонент принимает то, что собирает useChatUsage: занятый контекст последнего измеренного
 * прогона, системную часть (база первого прогона) и суммы по прогонам. Числа синтетические, но
 * связанные между собой так же, как у настоящего чата: cache miss + cache hit = total input
 * (17 700 + 63 400 = 81 100), а он с выходом — ровно Total (82 200). Разрыв между занятыми 21.1k и
 * Total намеренный: его объясняют шесть обращений к модели, каждое из которых оплачивает контекст
 * заново, — ради этого вкладка и существует.
 */
export const measuredChat = {
  usage: {
    current: { contextTokens: 21_100 },
    base: 9_400,
    totals: {
      outputTokens: 1_100,
      promptTokens: 81_100,
      cacheReadTokens: 63_400,
      cacheWriteTokens: 2_400,
      modelCalls: 6,
    },
    partial: false,
  },
};

/**
 * Загружена не вся история: числа относятся к прочитанной части, и вкладка обязана это сказать —
 * иначе они читаются как итог по всему чату. Кэша у прогонов нет вовсе (эндпоинт без него), и вход
 * тогда одной строкой «Input», без пары miss/hit.
 */
export const partialHistory = {
  usage: {
    current: { contextTokens: 8_300 },
    base: null,
    totals: {
      outputTokens: 640,
      promptTokens: 15_900,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      modelCalls: 2,
    },
    partial: true,
  },
};
