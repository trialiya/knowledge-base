import { formatCompactDateTime, formatRelativeTime } from './formatting';

describe('formatRelativeTime', () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date('2026-06-15T12:00:00Z') }));
  afterEach(() => vi.useRealTimers());

  test('within a day it is relative', () => {
    expect(formatRelativeTime('2026-06-15T11:30:00Z', 'en')).toBe('30 minutes ago');
    expect(formatRelativeTime('2026-06-15T09:00:00Z', 'en')).toBe('3 hours ago');
  });

  // Две даты с разницей в годы не должны читаться одинаково («5 мар.»).
  test('past a day it is a date, with the year once it differs', () => {
    expect(formatRelativeTime('2026-03-05T10:00:00Z', 'en')).toBe('Mar 5');
    expect(formatRelativeTime('2023-03-05T10:00:00Z', 'en')).toBe('Mar 5, 2023');
  });

  // Часы разошлись: коммит «из будущего» — всё же дата, а не пустая подпись.
  test('a moment in the future is still a date', () => {
    expect(formatRelativeTime('2026-06-16T12:00:00Z', 'en')).toBe('Jun 16');
  });

  test('empty or broken input is null', () => {
    expect(formatRelativeTime(null, 'en')).toBeNull();
    expect(formatRelativeTime('nonsense', 'en')).toBeNull();
  });
});

describe('formatCompactDateTime', () => {
  test('digits only, minutes without seconds', () => {
    const value = new Date(2026, 4, 20, 6, 58, 49).toISOString();
    expect(formatCompactDateTime(value, 'ru')).toBe('20.05.2026, 06:58');
  });

  test('empty and broken values give null', () => {
    expect(formatCompactDateTime(null, 'ru')).toBeNull();
    expect(formatCompactDateTime('nonsense', 'ru')).toBeNull();
  });
});
