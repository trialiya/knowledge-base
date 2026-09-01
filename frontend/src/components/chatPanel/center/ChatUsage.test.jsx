import { render, screen } from '@testing-library/react';
import ChatUsage from './ChatUsage';

// Ключи вместо переводов: кейс здесь — какие строки собраны и с какими числами, а не как они
// подписаны. Единственное, что подставляется, — параметры: без них не видно ни Total, ни долей.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, vars) => (vars ? `${key} ${JSON.stringify(vars)}` : key),
    i18n: { language: 'ru' },
  }),
}));

const usage = (spent, totals = {}) => ({
  usage: {
    current: { contextTokens: 21_100 },
    totals: { baseContextTokens: 9_400, spent, subagentRuns: 0, subagentSpent: null, ...totals },
  },
});

const withCache = {
  outputTokens: 1_100,
  promptTokens: 81_100,
  cacheReadTokens: 63_400,
  cacheWriteTokens: 2_400,
  totalTokens: 82_200,
  modelCalls: 6,
};

/** Значение строки: сама строка вкладки — это метка и значение рядом (см. InfoList). */
const valueOf = (labelKey) =>
  screen.getByText(labelKey).closest('.info-list__row').querySelector('.info-list__value-text');

describe('ChatUsage', () => {
  test('вход разбит по ставкам провайдера, и вместе с выходом это ровно Total', () => {
    render(<ChatUsage {...usage(withCache)} />);

    // 81 100 − 63 400 = 17 700 мимо кэша; 81 100 + 1 100 = 82 200 всего.
    expect(valueOf('usage.cacheMiss')).toHaveTextContent('17.7k');
    expect(valueOf('usage.cacheHit')).toHaveTextContent('63.4k');
    expect(valueOf('usage.cacheHit')).toHaveTextContent('78');
    expect(valueOf('usage.total')).toHaveTextContent('82.2k');
    expect(screen.queryByText('usage.input')).toBeNull();
  });

  // У модели с reasoning-токенами провайдер считает свой total больше суммы частей, и платит за
  // разницу клиент: Total показывает её, а строка Reasoning объясняет, откуда она взялась, — иначе
  // столбец не сходился бы со строками над ним.
  test('Total берётся по счёту провайдера, а невидимый выход объясняет строка Reasoning', () => {
    render(<ChatUsage {...usage({ ...withCache, totalTokens: 96_000 })} />);

    expect(valueOf('usage.total')).toHaveTextContent('96.0k');
    // 96 000 − 81 100 − 1 100 = 13 800.
    expect(valueOf('usage.reasoning')).toHaveTextContent('13.8k');
  });

  test('без reasoning-токенов строки нет: ноль отвечал бы на незаданный вопрос', () => {
    render(<ChatUsage {...usage(withCache)} />);

    expect(screen.queryByText('usage.reasoning')).toBeNull();
  });

  // Эндпоинт чата может usage не отдавать, а эндпоинт суб-агента — отдавать: спрятать его деньги
  // за «ничего не измерено» значило бы соврать.
  test('деньги суб-агента показываются и без единого замера у модели чата', () => {
    render(
      <ChatUsage
        usage={{
          current: null,
          totals: {
            baseContextTokens: null,
            spent: null,
            subagentRuns: 2,
            subagentSpent: { promptTokens: 24_000, outputTokens: 900, modelCalls: 5 },
          },
        }}
      />,
    );

    expect(screen.queryByText('usage.empty')).toBeNull();
    expect(valueOf('usage.subagentInput')).toHaveTextContent('24.0k');
  });

  test('системная часть — долей от занятого контекста: вопрос к ней про отношение', () => {
    render(<ChatUsage {...usage(withCache)} />);

    // 9 400 от 21 100 — 45%.
    expect(valueOf('usage.system')).toHaveTextContent('9.4k');
    expect(valueOf('usage.system')).toHaveTextContent('45');
  });

  test('без кэша вход одной строкой: «Input (Cache miss)» без пары читался бы как часть чего-то', () => {
    render(<ChatUsage {...usage({ outputTokens: 640, promptTokens: 15_900, cacheReadTokens: 0, modelCalls: 2 })} />);

    expect(valueOf('usage.input')).toHaveTextContent('15.9k');
    expect(screen.queryByText('usage.cacheMiss')).toBeNull();
    expect(screen.queryByText('usage.cacheHit')).toBeNull();
    expect(screen.queryByText('usage.cacheWrite')).toBeNull();
  });

  test('у неизмеренного чата вкладка пустая: ноль здесь был бы неправдой', () => {
    render(<ChatUsage usage={{ current: null, totals: null }} />);

    expect(screen.getByText('usage.empty')).toBeInTheDocument();
  });

  // Своя модель — свой тариф, поэтому числа суб-агента стоят отдельно от счёта чата, а сноска
  // говорит, что в Total выше они не входят.
  test('деньги суб-агента отдельным блоком со сноской, и не внутри Total', () => {
    render(
      <ChatUsage
        {...usage(withCache, {
          subagentRuns: 3,
          subagentSpent: { outputTokens: 900, promptTokens: 24_000, totalTokens: 24_900, modelCalls: 7 },
        })}
      />,
    );

    expect(valueOf('usage.subagentRuns')).toHaveTextContent('3');
    expect(valueOf('usage.subagentInput')).toHaveTextContent('24.0k');
    expect(valueOf('usage.subagentOutput')).toHaveTextContent('900');
    expect(valueOf('usage.subagentTotal')).toHaveTextContent('24.9k');
    expect(valueOf('usage.total')).toHaveTextContent('82.2k');
    expect(screen.getByText('usage.subagentNote')).toBeInTheDocument();
  });

  test('без суб-агента блока нет вовсе', () => {
    render(<ChatUsage {...usage(withCache)} />);

    expect(screen.queryByText('usage.subagentRuns')).toBeNull();
    expect(screen.queryByText('usage.subagentNote')).toBeNull();
  });
});
