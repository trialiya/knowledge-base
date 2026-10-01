import commitHits from './commitHits';

const commit = (over) => ({
  hash: 'abc1234def5678',
  shortHash: 'abc1234',
  author: 'Ann',
  date: '2026-09-01T10:00:00+03:00',
  message: 'Subject',
  ...over,
});

const match = (over) => ({ commit: commit(), subjectMatch: false, hashMatch: false, lines: [], ...over });

test('строки и признаки совпадения от бэкенда — в карточке, заголовок и строка — два совпадения', () => {
  const lines = [{ line: 2, text: 'Search by body' }];
  const { commits, total } = commitHits({
    truncated: false,
    commits: [match({ commit: commit({ message: 'Fix search' }), subjectMatch: true, lines })],
  });

  expect(commits[0]).toMatchObject({ message: 'Fix search', subjectMatch: true, hashMatch: false, lines });
  expect(total).toBe(2);
});

test('коммит, найденный только по хешу, считается за одно совпадение', () => {
  const { commits, total } = commitHits({ truncated: false, commits: [match({ hashMatch: true })] });

  expect(commits[0].hashMatch).toBe(true);
  expect(total).toBe(1);
});

test('обрезку выдачи решает бэкенд — и пустая выдача может быть обрезанной', () => {
  expect(commitHits({ truncated: true, commits: [] })).toEqual({ total: 0, truncated: true, commits: [] });
  expect(commitHits({ truncated: false, commits: [match()] }).truncated).toBe(false);
});
