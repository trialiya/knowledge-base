import { act, render } from '@testing-library/react';
import RelativeTime from './RelativeTime';
import { formatDateTime } from '@/utils/formatting';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ i18n: { language: 'ru' } }) }));

describe('RelativeTime', () => {
  afterEach(() => vi.useRealTimers());

  it('относительное время в тексте, точное — в подсказке и в dateTime', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-09-21T12:00:00Z'));
    const value = '2026-09-21T10:00:00Z';

    const { container } = render(<RelativeTime className="x" value={value} />);
    const time = container.querySelector('time.x');

    expect(time).toHaveTextContent('2 часа назад');
    expect(time).toHaveAttribute('dateTime', '2026-09-21T10:00:00.000Z');
    expect(time).toHaveAttribute('title', formatDateTime(value, 'ru'));
  });

  it('в атрибут datetime — ISO, даже из Date и не-ISO строки', () => {
    const { container } = render(
      <>
        <RelativeTime value={new Date('2026-09-21T10:00:00Z')} />
        <RelativeTime value="2026-09-21 10:00:00Z" />
      </>,
    );
    const attrs = [...container.querySelectorAll('time')].map((el) => el.getAttribute('dateTime'));
    expect(attrs).toEqual(['2026-09-21T10:00:00.000Z', '2026-09-21T10:00:00.000Z']);
  });

  it('пустое и битое значение не рисует ничего', () => {
    const { container } = render(
      <>
        <RelativeTime value={null} />
        <RelativeTime value="nonsense" />
      </>,
    );
    expect(container).toBeEmptyDOMElement();
  });

  // Молчащий чат: новых пропсов нет, а подпись всё равно идёт вслед за часами.
  it('относительная подпись обновляется сама, без новых пропсов', () => {
    vi.useFakeTimers({ now: new Date('2026-09-21T12:00:30Z') });
    const { container } = render(<RelativeTime value="2026-09-21T11:59:00Z" />);
    expect(container.querySelector('time')).toHaveTextContent('1 минуту назад');

    act(() => vi.advanceTimersByTime(60_000));
    expect(container.querySelector('time')).toHaveTextContent('2 минуты назад');
  });

  // Фоновая вкладка: таймер браузер придушил, а часы ушли вперёд — пересчёт по возвращении.
  it('пересчитывается при возвращении во вкладку, не дожидаясь тика', () => {
    vi.useFakeTimers({ now: new Date('2026-09-21T12:00:00Z') });
    const { container } = render(<RelativeTime value="2026-09-21T11:55:00Z" />);
    expect(container.querySelector('time')).toHaveTextContent('5 минут назад');

    vi.setSystemTime(new Date('2026-09-21T12:30:00Z'));
    act(() => window.dispatchEvent(new Event('focus')));
    expect(container.querySelector('time')).toHaveTextContent('35 минут назад');
  });

  it('дата старше суток таймер не заводит', () => {
    vi.useFakeTimers({ now: new Date('2026-09-21T12:00:00Z') });
    render(<RelativeTime value="2026-09-01T10:00:00Z" />);
    expect(vi.getTimerCount()).toBe(0);
  });
});
