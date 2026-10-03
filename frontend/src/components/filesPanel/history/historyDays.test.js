import { dayLabel, groupByDay } from './historyDays';

const at = (y, m, d, h = 12) => new Date(y, m - 1, d, h).toISOString();

describe('groupByDay', () => {
  test('consecutive commits of one local day share a group, in the order git gave them', () => {
    const commits = [
      { hash: 'c', date: at(2026, 10, 2, 21) },
      { hash: 'b', date: at(2026, 10, 2, 1) },
      { hash: 'a', date: at(2026, 10, 1, 23) },
    ];

    const groups = groupByDay(commits);

    expect(groups.map((g) => g.commits.map((c) => c.hash))).toEqual([['c', 'b'], ['a']]);
  });

  // Обход истории не сортирован по дате (слияние приносит старые коммиты): день,
  // встретившийся снова, — новая группа, а не перестановка коммитов.
  test('a day met again after another one starts a new group', () => {
    const commits = [
      { hash: 'c', date: at(2026, 10, 2) },
      { hash: 'b', date: at(2026, 9, 30) },
      { hash: 'a', date: at(2026, 10, 2) },
    ];

    expect(groupByDay(commits)).toHaveLength(3);
  });
});

describe('dayLabel', () => {
  const now = new Date(2026, 9, 3, 10);

  test('today and yesterday are words, older days are dates', () => {
    expect(dayLabel(at(2026, 10, 3), 'ru', now)).toEqual({ key: 'history.today' });
    expect(dayLabel(at(2026, 10, 2), 'ru', now)).toEqual({ key: 'history.yesterday' });
    expect(dayLabel(at(2026, 10, 1), 'en', now).text).toBe('October 1');
  });

  test('a date from another year carries the year', () => {
    expect(dayLabel(at(2025, 12, 31), 'en', now).text).toBe('December 31, 2025');
  });
});
