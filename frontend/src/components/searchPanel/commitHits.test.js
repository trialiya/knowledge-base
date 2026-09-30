import commitHits from './commitHits';

const commit = (over) => ({
  hash: 'abc1234def5678',
  shortHash: 'abc1234',
  author: 'Ann',
  date: '2026-09-01T10:00:00+03:00',
  message: 'Subject',
  ...over,
});

test('строки описания с запросом становятся строками карточки, без учёта регистра', () => {
  const { commits, total } = commitHits(
    [commit({ message: 'Fix search', body: 'first line\nSearch by body\nlast' })],
    'search',
    50,
  );

  expect(commits[0].subjectMatch).toBe(true);
  expect(commits[0].lines).toEqual([{ line: 2, text: 'Search by body' }]);
  // Заголовок и строка описания — два совпадения.
  expect(total).toBe(2);
});

test('коммит, найденный только по хешу, помечен и считается за одно совпадение', () => {
  const { commits, total } = commitHits([commit({ body: 'nothing' })], 'abc12', 50);

  expect(commits[0].hashMatch).toBe(true);
  expect(commits[0].lines).toEqual([]);
  expect(total).toBe(1);
});

test('выдача длиной в лимит считается обрезанной', () => {
  expect(commitHits([commit(), commit({ hash: 'ff' })], 'subject', 2).truncated).toBe(true);
  expect(commitHits([commit()], 'subject', 2).truncated).toBe(false);
});
