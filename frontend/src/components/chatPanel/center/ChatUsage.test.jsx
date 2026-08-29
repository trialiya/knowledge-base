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

const usage = (totals, rest = {}) => ({
  usage: { current: { contextTokens: 21_100 }, base: 9_400, totals, partial: false, ...rest },
});

const withCache = {
  outputTokens: 1_100,
  promptTokens: 81_100,
  cacheReadTokens: 63_400,
  cacheWriteTokens: 2_400,
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
    render(<ChatUsage usage={{ current: null, totals: null, base: null, partial: false }} />);

    expect(screen.getByText('usage.empty')).toBeInTheDocument();
  });

  test('о частично загруженной истории вкладка говорит сноской', () => {
    render(<ChatUsage {...usage(withCache, { partial: true })} />);

    expect(screen.getByText('usage.partial')).toBeInTheDocument();
  });
});
