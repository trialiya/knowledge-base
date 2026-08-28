import { act, render } from '@testing-library/react';
import RunStatus from './RunStatus';

// Строка над полем ввода на время прогона: таймер обязан идти сам (у прогона нет событий
// «прошла секунда»), а её половины — независимы: якорь без замера и замер без якоря оба легальны.
// role/live-региона у строки нет намеренно — скринридер не должен слышать каждую секунду, поэтому
// и запросы здесь по классу, а не по роли.

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, params) => (params?.tokens != null ? `+${params.tokens} input` : key),
  }),
}));

const statusOf = (container) => container.querySelector('.run-status');

describe('RunStatus', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-28T12:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  test('таймер отсчитывает от якоря и тикает секундами', () => {
    const { container } = render(<RunStatus startedAt={Date.now() - 65_000} inputGrowth={null} />);

    expect(statusOf(container)).toHaveTextContent('1:05');

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(statusOf(container)).toHaveTextContent('1:07');
  });

  test('после часа появляется разряд часов', () => {
    const { container } = render(<RunStatus startedAt={Date.now() - 3_700_000} inputGrowth={null} />);

    expect(statusOf(container)).toHaveTextContent('1:01:40');
  });

  test('прирост показывается рядом с таймером, а без замера — не показывается', () => {
    const { container, rerender } = render(<RunStatus startedAt={Date.now()} inputGrowth={6400} />);
    expect(statusOf(container)).toHaveTextContent('+6.4k input');

    rerender(<RunStatus startedAt={Date.now()} inputGrowth={null} />);
    expect(statusOf(container)).not.toHaveTextContent('input');
  });

  test('без якоря остаётся один прирост — и секундный интервал не заводится', () => {
    const { container, rerender } = render(<RunStatus startedAt={null} inputGrowth={6400} />);
    expect(statusOf(container)).toHaveTextContent('+6.4k input');
    expect(statusOf(container)).not.toHaveTextContent(':');
    // Отсчитывать нечего — тик без якоря лишь перерисовывал бы строку впустую.
    expect(vi.getTimerCount()).toBe(0);

    rerender(<RunStatus startedAt={null} inputGrowth={null} />);
    expect(statusOf(container)).toBeNull();
  });
});
