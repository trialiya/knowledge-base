import { originKey } from './originAnswer';

it('originKey различает ревизию, проект, строку и запрос', () => {
  const base = { path: 'a.txt', line: 3, query: 'x', rev: '', project: '' };
  const keys = new Set(
    [base, { ...base, rev: 'v1' }, { ...base, project: 'p' }, { ...base, line: 4 }, { ...base, query: 'y' }].map(
      originKey,
    ),
  );
  expect(keys.size).toBe(5);
});
