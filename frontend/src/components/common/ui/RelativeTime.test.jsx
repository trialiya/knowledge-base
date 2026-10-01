import { render } from '@testing-library/react';
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
});
