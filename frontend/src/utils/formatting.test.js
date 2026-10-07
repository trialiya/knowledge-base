import {
  formatCompactDateTime,
  formatDate,
  formatLongDateTime,
  formatRelativeTime,
  isRelativeTimeLive,
} from './formatting';

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

  // Отметка своей вкладки после последнего тика useNow, или часы сервера чуть впереди.
  test('a moment seconds ahead is now, not a date', () => {
    expect(formatRelativeTime('2026-06-15T12:00:00.800Z', 'en')).toBe('this minute');
  });

  test('counts from the given moment instead of the clock', () => {
    const at = new Date('2026-06-15T13:00:00Z').getTime();
    expect(formatRelativeTime('2026-06-15T11:30:00Z', 'en', at)).toBe('1 hour ago');
  });
});

describe('isRelativeTimeLive', () => {
  const now = new Date('2026-06-15T12:00:00Z').getTime();

  test('live while the label is relative, or may become relative', () => {
    expect(isRelativeTimeLive('2026-06-15T11:59:00Z', now)).toBe(true);
    expect(isRelativeTimeLive('2026-06-15T13:00:00Z', now)).toBe(true);
  });

  test('a date past a day, and empty or broken input, is not', () => {
    expect(isRelativeTimeLive('2026-06-13T12:00:00Z', now)).toBe(false);
    expect(isRelativeTimeLive(null, now)).toBe(false);
    expect(isRelativeTimeLive('nonsense', now)).toBe(false);
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

describe('formatDate', () => {
  test('date only, no time', () => {
    expect(formatDate(new Date(2026, 4, 20, 6, 58), 'ru')).toBe('20.05.2026');
  });

  test('empty and broken values give null', () => {
    expect(formatDate('', 'ru')).toBeNull();
    expect(formatDate('nonsense', 'ru')).toBeNull();
  });
});

describe('formatLongDateTime', () => {
  test('month in words, minutes without seconds', () => {
    const value = new Date(2026, 4, 20, 6, 58, 49).toISOString();
    expect(formatLongDateTime(value, 'ru')).toBe('20 мая 2026 г. в 06:58');
  });

  test('empty and broken values give null', () => {
    expect(formatLongDateTime(undefined, 'ru')).toBeNull();
    expect(formatLongDateTime('nonsense', 'ru')).toBeNull();
  });
});
