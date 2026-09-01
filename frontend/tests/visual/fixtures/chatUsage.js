/**
 * Фикстуры вкладки «Usage» правой панели чата (chatPanel/center/ChatUsage.jsx).
 *
 * Компонент принимает то, что собирает useChatUsage: занятый контекст последнего измеренного
 * прогона (его считает фронт по ленте) и счёт за весь чат (его отдаёт GET /chats/{id}/usage).
 * Числа синтетические, но связанные между собой так же, как у настоящего чата: cache miss + cache
 * hit = total input (17 700 + 63 400 = 81 100), а он с выходом — ровно Total (82 200). Разрыв
 * между занятыми 21.1k и Total намеренный: его объясняют шесть обращений к модели, каждое из
 * которых оплачивает контекст заново, — ради этого вкладка и существует.
 */
export const measuredChat = {
  usage: {
    current: { contextTokens: 21_100 },
    totals: {
      baseContextTokens: 9_400,
      spent: {
        outputTokens: 1_100,
        promptTokens: 81_100,
        cacheReadTokens: 63_400,
        cacheWriteTokens: 2_400,
        totalTokens: 82_200,
        modelCalls: 6,
      },
      subagentRuns: 0,
      subagentSpent: null,
    },
  },
};

/**
 * Тот же чат, но модель считает reasoning-токены, а поиск по коду ходил к суб-агенту.
 *
 * Reasoning — невидимая часть выхода: провайдерский total (96 000) больше входа с выходом
 * (82 200) ровно на неё, и без своей строки (13 800) столбец не сходился бы. Суб-агент стоит
 * отдельным блоком со своим заголовком: у него своя модель и свой тариф, и в Total выше его
 * деньги не входят — об этом же говорит сноска под блоком.
 */
export const subagentSpending = {
  usage: {
    current: { contextTokens: 21_100 },
    totals: {
      baseContextTokens: 9_400,
      spent: {
        outputTokens: 1_100,
        promptTokens: 81_100,
        cacheReadTokens: 63_400,
        cacheWriteTokens: 2_400,
        totalTokens: 96_000,
        modelCalls: 6,
      },
      subagentRuns: 3,
      subagentSpent: {
        outputTokens: 900,
        promptTokens: 24_000,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        totalTokens: 24_900,
        modelCalls: 7,
      },
    },
  },
};
