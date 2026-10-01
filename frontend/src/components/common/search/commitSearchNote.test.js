import commitSearchNote from './commitSearchNote';

describe('commitSearchNote', () => {
  it('полная выдача — без подписи', () => {
    expect(commitSearchNote({ count: 10, limit: 10, truncated: false })).toBeNull();
  });

  it('лимит заполнен — уточнить запрос', () => {
    expect(commitSearchNote({ count: 10, limit: 10, truncated: true })).toBe('common:commitSearch.refine');
  });

  it('выдача короче лимита — старые коммиты не просмотрены', () => {
    expect(commitSearchNote({ count: 3, limit: 10, truncated: true })).toBe('common:commitSearch.olderNotSearched');
  });

  it('пустая обрезанная выдача — «нет в просмотренной части», а не «нет вовсе»', () => {
    expect(commitSearchNote({ count: 0, limit: 10, truncated: true })).toBe('common:commitSearch.nothingInSearched');
  });
});
